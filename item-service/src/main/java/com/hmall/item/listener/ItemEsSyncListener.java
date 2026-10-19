package com.hmall.item.listener;

import com.hmall.common.constants.ItemEsConstants;
import com.hmall.common.constants.MQConstants;
import com.hmall.item.domain.dto.ItemEsMessage;
import com.hmall.item.service.ItemEsService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 商品变更 -> ES 同步消费者：
 * 业务服务（item-service 内）任何商品新增/修改/删除/库存变化都发同一队列，
 * 本监听器按消息 opType 决定执行哪种 ES 操作：
 * - delete：删除ES文档
 * - save / update / stock：按id覆盖写入文档
 * 手动确认 + 系统异常由Spring重试3次，耗尽进死信队列（item.es.dlx.queue）待人工排查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemEsSyncListener {

    private final ItemEsService itemEsService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.ITEM_ES_QUEUE, durable = "true",
                    arguments = @Argument(name = "x-dead-letter-exchange", value = MQConstants.ITEM_ES_DLX_EXCHANGE)),
            exchange = @Exchange(name = MQConstants.ITEM_ES_EXCHANGE),
            key = MQConstants.ITEM_ES_KEY
    ))
    public void syncItemToEs(@Payload ItemEsMessage msg, Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            // 按消息类型执行对应ES操作
            if (ItemEsConstants.ES_OP_DELETE.equals(msg.getOpType())) {
                itemEsService.deleteItem(msg.getId());
                log.info("商品变更消息[delete]处理完成，id={}", msg.getId());
            } else {
                itemEsService.syncItem(msg.getId());
                log.info("商品变更消息[{}]处理完成，id={}", msg.getOpType(), msg.getId());
            }
            // 处理成功：手动确认
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 系统异常：不确认，由Spring重试（max-attempts=3），耗尽后进入死信队列
            log.error("商品变更->ES同步消息处理失败，opType={}，id={}", msg.getOpType(), msg.getId(), e);
            throw e;
        }
    }
}
