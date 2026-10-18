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
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /** 商品缓存key前缀 */
    private static final String CACHE_ITEM_KEY_PREFIX = "cache:item:";
    /** 商品缓存有效期：30分钟 */
    private static final Duration CACHE_ITEM_TTL = Duration.ofMinutes(30);
    /** 空值占位符，防止缓存穿透 */
    private static final String CACHE_NULL_VALUE = "";
    /** 空值占位有效期：5分钟 */
    private static final Duration CACHE_NULL_TTL = Duration.ofMinutes(5);

    @Override
    public ItemDTO queryItemById(Long id) {
        // 1.查询缓存
        String key = getKey(id);
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.缓存命中：空值占位直接返回null，正常数据反序列化返回
        if (json != null) {
            return StrUtil.isBlank(json) ? null : deserialize(json);
        }
        // 3.缓存未命中，查询数据库
        Item item = getById(id);
        // 4.写回缓存：商品不存在则写空值占位，防止缓存穿透
        if (item == null) {
            stringRedisTemplate.opsForValue().set(key, CACHE_NULL_VALUE, CACHE_NULL_TTL);
            return null;
        }
        ItemDTO dto = BeanUtils.copyBean(item, ItemDTO.class);
        stringRedisTemplate.opsForValue().set(key, serialize(dto), CACHE_ITEM_TTL);
        return dto;
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
        String sqlStatement = "com.hmall.item.mapper.ItemMapper.updateStock";
        boolean r = false;
        try {
            r = executeBatch(items, (sqlSession, entity) -> sqlSession.update(sqlStatement, entity));
        } catch (Exception e) {
            throw new BizIllegalException("更新库存异常，可能是库存不足!", e);
        }
        if (!r) {
            throw new BizIllegalException("库存不足！");
        }
        // 扣减库存成功后删除缓存，保证缓存与数据库最终一致
        items.forEach(item -> deleteCache(item.getItemId()));
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
