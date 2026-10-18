package com.hmall.common.constants;

import java.time.Duration;

/**
 * Redis 缓存相关常量
 */
public class CacheConstants {

    private CacheConstants() {
    }

    /** 商品缓存key前缀 */
    public static final String CACHE_ITEM_KEY_PREFIX = "cache:item:";
    /** 商品缓存有效期：30分钟 */
    public static final Duration CACHE_ITEM_TTL = Duration.ofMinutes(30);
    /** 空值占位符，防止缓存穿透 */
    public static final String CACHE_NULL_VALUE = "";
    /** 空值占位有效期：5分钟 */
    public static final Duration CACHE_NULL_TTL = Duration.ofMinutes(5);
    /** 商品缓存互斥锁key前缀（解决缓存击穿） */
    public static final String CACHE_ITEM_LOCK_KEY_PREFIX = "lock:item:";
    /** 互斥锁有效期：10秒 */
    public static final Duration CACHE_ITEM_LOCK_TTL = Duration.ofSeconds(10);
    /** 看门狗续期间隔：TTL的三分之一，业务未执行完前定期续期，避免锁提前过期 */
    public static final Duration CACHE_ITEM_LOCK_RENEW_INTERVAL = Duration.ofSeconds(3);
    /** 未抢到锁时的最大重试次数：需覆盖锁的10秒过期时间，保证持锁线程崩溃后锁被回收时能接力抢到 */
    public static final int CACHE_ITEM_LOCK_RETRY_TIMES = 300;
    /** 未抢到锁时的重试间隔 */
    public static final Duration CACHE_ITEM_LOCK_RETRY_INTERVAL = Duration.ofMillis(50);
}
