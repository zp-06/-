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
}
