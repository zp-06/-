package com.hmall.item.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.BeanUtils;

import com.hmall.item.domain.dto.ItemDTO;
import com.hmall.item.domain.dto.OrderDetailDTO;
import com.hmall.item.domain.po.Item;
import com.hmall.item.mapper.ItemMapper;
import com.hmall.item.service.IItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmall.common.constants.CacheConstants.CACHE_ITEM_KEY_PREFIX;
import static com.hmall.common.constants.CacheConstants.CACHE_ITEM_LOCK_KEY_PREFIX;
import static com.hmall.common.constants.CacheConstants.CACHE_ITEM_LOCK_RENEW_INTERVAL;
import static com.hmall.common.constants.CacheConstants.CACHE_ITEM_LOCK_RETRY_INTERVAL;
import static com.hmall.common.constants.CacheConstants.CACHE_ITEM_LOCK_RETRY_TIMES;
import static com.hmall.common.constants.CacheConstants.CACHE_ITEM_LOCK_TTL;
import static com.hmall.common.constants.CacheConstants.CACHE_ITEM_TTL;
import static com.hmall.common.constants.CacheConstants.CACHE_NULL_TTL;
import static com.hmall.common.constants.CacheConstants.CACHE_NULL_VALUE;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    /** 释放锁的Lua脚本：值仍等于自己的token才删除，防止误删其他线程新获取的锁 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** 续期锁的Lua脚本：值仍等于自己的token才续期，防止续到其他线程新获取的锁上 */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /** 看门狗线程池：单线程、守护线程，用于持锁期间定期续期 */
    private static final ScheduledExecutorService WATCHDOG_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cache-lock-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public ItemDTO queryItemById(Long id) {
        return queryItemByIdWithMutex(id, CACHE_ITEM_LOCK_RETRY_TIMES);
    }

    /**
     * 互斥锁版查询：未抢到锁的线程休眠后递归重试（重新查缓存 + 重新抢锁），
     * 持锁线程启动看门狗定期续期，防止业务未执行完锁就过期
     */
    private ItemDTO queryItemByIdWithMutex(Long id, int retryCount) {
        // 1.查询缓存
        String key = getKey(id);
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.缓存命中（含空值占位）直接返回
        if (json != null) {
            return StrUtil.isBlank(json) ? null : deserialize(json);
        }
        // 3.缓存未命中：尝试获取互斥锁，只允许一个线程重建缓存，解决缓存击穿
        String lockKey = getLockKey(id);
        String lockToken = UUID.randomUUID().toString();
        boolean locked = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, CACHE_ITEM_LOCK_TTL));
        // 4.未抢到锁：休眠后递归重试（重新查缓存、重新抢锁），实现接力重建
        if (!locked) {
            if (retryCount <= 0) {
                // 重试耗尽兜底：直接查库返回，避免请求无限等待
                Item item = getById(id);
                return item == null ? null : BeanUtils.copyBean(item, ItemDTO.class);
            }
            try {
                Thread.sleep(CACHE_ITEM_LOCK_RETRY_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待缓存锁被中断", e);
            }
            return queryItemByIdWithMutex(id, retryCount - 1);
        }
        // 5.抢到锁：启动看门狗定期续期，保证业务执行期间锁不会提前过期
        ScheduledFuture<?> watchdog = startWatchdog(lockKey, lockToken);
        try {
            // 5.1 双重检查：排队抢到锁期间，缓存可能已被其他线程重建
            json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                return StrUtil.isBlank(json) ? null : deserialize(json);
            }
            // 5.2 查数据库并回填缓存
            Item item = getById(id);
            if (item == null) {
                // 商品不存在：写空值占位，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, CACHE_NULL_VALUE, CACHE_NULL_TTL);
                return null;
            }
            ItemDTO dto = BeanUtils.copyBean(item, ItemDTO.class);
            stringRedisTemplate.opsForValue().set(key, serialize(dto), CACHE_ITEM_TTL);
            return dto;
        } finally {
            // 6.先停止看门狗，再释放锁（Lua校验token，防止误删他人锁）
            if (watchdog != null) {
                watchdog.cancel(true);
            }
            unlock(lockKey, lockToken);
        }
    }

    @Override
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        // 1.去重
        List<Long> idList = ids.stream().distinct().collect(Collectors.toList());
        if (idList.isEmpty()) {
            return Collections.emptyList();
        }
        // 2.批量查询缓存
        List<String> keys = idList.stream().map(this::getKey).collect(Collectors.toList());
        List<String> cachedJsons = stringRedisTemplate.opsForValue().multiGet(keys);
        // 3.拆分命中与未命中：命中直接反序列化，未命中记录待查库
        Map<Long, ItemDTO> resultMap = new HashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < idList.size(); i++) {
            String json = cachedJsons.get(i);
            Long id = idList.get(i);
            if (json == null) {
                missIds.add(id);
            } else if (StrUtil.isNotBlank(json)) {
                resultMap.put(id, deserialize(json));
            }
        }
        // 4.未命中的id批量查库，并回填缓存（含空值占位）
        if (!missIds.isEmpty()) {
            Map<Long, Item> dbMap = listByIds(missIds).stream()
                    .collect(Collectors.toMap(Item::getId, i -> i));
            for (Long id : missIds) {
                Item item = dbMap.get(id);
                if (item == null) {
                    stringRedisTemplate.opsForValue().set(getKey(id), CACHE_NULL_VALUE, CACHE_NULL_TTL);
                } else {
                    ItemDTO dto = BeanUtils.copyBean(item, ItemDTO.class);
                    resultMap.put(id, dto);
                    stringRedisTemplate.opsForValue().set(getKey(id), serialize(dto), CACHE_ITEM_TTL);
                }
            }
        }
        // 5.按传入顺序返回
        return idList.stream().filter(resultMap::containsKey).map(resultMap::get).collect(Collectors.toList());
    }

    @Override
    public void deductStock(List<OrderDetailDTO> items) {
        // 乐观锁（CAS 条件更新）：UPDATE ... WHERE stock >= num，原子扣减，防止超卖
        // 逐条执行以便读取影响行数；返回 0 说明库存不足或商品不存在，直接抛异常回滚
        for (OrderDetailDTO item : items) {
            int rows = baseMapper.updateStock(item);
            if (rows == 0) {
                throw new BizIllegalException("库存不足！");
            }
        }
        // 扣减库存成功后删除缓存，保证缓存与数据库最终一致
        items.forEach(detail -> deleteCache(detail.getItemId()));
    }

    @Override
    public boolean updateById(Item entity) {
        boolean updated = super.updateById(entity);
        if (updated) {
            deleteCache(entity.getId());
        }
        return updated;
    }

    @Override
    public boolean removeById(Serializable id) {
        boolean removed = super.removeById(id);
        deleteCache(id);
        return removed;
    }

    private String getKey(Long id) {
        return CACHE_ITEM_KEY_PREFIX + id;
    }

    private String getLockKey(Long id) {
        return CACHE_ITEM_LOCK_KEY_PREFIX + id;
    }

    private void unlock(String lockKey, String lockToken) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockToken);
    }

    /**
     * 启动看门狗：每 RENEW_INTERVAL 续期一次锁（TTL 重置为 10s），
     * 业务执行期间锁不会提前过期；续期同样校验 token，不会续到别人的锁上
     */
    private ScheduledFuture<?> startWatchdog(String lockKey, String lockToken) {
        long intervalSeconds = CACHE_ITEM_LOCK_RENEW_INTERVAL.toSeconds();
        return WATCHDOG_EXECUTOR.scheduleWithFixedDelay(
                () -> {
                    try {
                        renewLock(lockKey, lockToken);
                    } catch (Exception e) {
                        log.warn("缓存锁续期失败, lockKey={}", lockKey, e);
                    }
                },
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void renewLock(String lockKey, String lockToken) {
        stringRedisTemplate.execute(
                RENEW_SCRIPT,
                Collections.singletonList(lockKey),
                lockToken,
                String.valueOf(CACHE_ITEM_LOCK_TTL.toSeconds()));
    }

    private void deleteCache(Serializable id) {
        stringRedisTemplate.delete(getKey((Long) id));
    }

    private String serialize(ItemDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new RuntimeException("商品缓存序列化失败", e);
        }
    }

    private ItemDTO deserialize(String json) {
        try {
            return objectMapper.readValue(json, ItemDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("商品缓存反序列化失败", e);
        }
    }
}
