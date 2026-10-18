package com.hmall.trade.listener;

import com.hmall.common.exception.BadRequestException;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.trade.service.IOrderService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class PayStatusListener {

    private final IOrderService orderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "trade.pay.success.queue", durable = "true",
                    arguments = @Argument(name = "x-dead-letter-exchange", value = "trade.pay.dlx.exchange")),
            exchange = @Exchange(name = "pay.direct"),
            key = "pay.success"
    ))
    public void listenPaySuccess(@Payload Long orderId, Message message, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            orderService.markOrderPaySuccess(orderId);
            // 1.处理成功：手动确认，从队列移除
            channel.basicAck(deliveryTag, false);
        } catch (BizIllegalException | BadRequestException e) {
            // 2.业务异常（如订单状态不允许处理）：消息本身没问题，确认消费并记录日志，避免无限重试
            log.error("处理支付成功消息出现业务异常，orderId={}，原因={}", orderId, e.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 3.系统异常（如数据库不可用）：不确认，抛出异常由Spring重试（max-attempts=3），
            //    重试耗尽后消息被拒收（requeue=false）进入死信队列 trade.pay.dlx.queue
            log.error("处理支付成功消息出现系统异常，orderId={}", orderId, e);
            throw e;
        }
    }
}
