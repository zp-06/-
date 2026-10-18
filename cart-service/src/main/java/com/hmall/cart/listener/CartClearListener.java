package com.hmall.cart.listener;

import com.hmall.cart.service.ICartService;
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
import java.util.Collection;

@Slf4j
@Component
@RequiredArgsConstructor
public class CartClearListener {
    private final ICartService cartService;
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "cart.clear.queue", durable = "true",
                    arguments = @Argument(name = "x-dead-letter-exchange", value = "cart.dlx.exchange")),
            exchange = @Exchange(name = "trade.topic"),
            key = "order.create"
    ))
    public void removeByItemIds(@Payload Collection<Long> itemIds, Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            cartService.removeByItemIds(itemIds);
            // 处理成功：手动确认
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 系统异常：不确认，由Spring重试（max-attempts=3），耗尽后进入死信队列 cart.dlx.queue
            log.error("清理购物车消息处理失败，itemIds={}", itemIds, e);
            throw e;
        }
    }
}
