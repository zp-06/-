package com.hmall.common.constants;

public interface MQConstants {
    /** 延迟消息1 **/
    String DELAY_EXCHANGE_NAME = "trade.delay.direct";

    String DELAY_ORDER_QUEUE_NAME = "trade.delay.order.queue";

    String DELAY_ORDER_KEY = "delay.order.query";

    /* ============ 商品变更 -> ES 同步 ============ */
    /** 商品变更交换机（direct） */
    String ITEM_ES_EXCHANGE = "item.es.exchange";
    /** ES同步队列 */
    String ITEM_ES_QUEUE = "item.es.sync.queue";
    /** 路由键 */
    String ITEM_ES_KEY = "item.es.sync";
    /** 死信交换机/队列（同步失败重试耗尽后暂存） */
    String ITEM_ES_DLX_EXCHANGE = "item.es.dlx.exchange";
    String ITEM_ES_DLX_QUEUE = "item.es.dlx.queue";
}
