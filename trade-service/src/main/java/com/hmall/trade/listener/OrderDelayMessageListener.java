package com.hmall.trade.listener;

import com.hmall.api.client.PayClient;
import com.hmall.api.dto.PayOrderDTO;

import com.hmall.common.constants.MQConstants;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.service.IOrderService;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDelayMessageListener {

    private final IOrderService orderService;
    private final PayClient payClient;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.DELAY_ORDER_QUEUE_NAME, durable = "true",
                    arguments = @Argument(name = "x-dead-letter-exchange", value = "trade.delay.dlx.exchange")),
            exchange = @Exchange(name = MQConstants.DELAY_EXCHANGE_NAME, delayed = "true"),
            key = MQConstants.DELAY_ORDER_KEY
    ))
    public void listenOrderDelayMessage(@Payload Long orderId, Channel channel,
                                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            // 1.查询订单
            Order order = orderService.getById(orderId);
            // 2.检测订单状态，判断是否已支付
            if(order == null || order.getStatus() != 1){
                // 订单不存在或者已经支付，无需处理
                channel.basicAck(deliveryTag, false);
                return;
            }
            // 3.未支付，需要查询支付流水状态
            PayOrderDTO payOrder = payClient.queryPayOrderByBizOrderNo(orderId);
            // 4.判断是否支付
            if(payOrder != null && payOrder.getStatus() == 3){
                // 4.1.已支付，标记订单状态为已支付
                orderService.markOrderPaySuccess(orderId);
            }else{
                // 4.2.未支付，取消订单，回复库存
                orderService.cancelOrder(orderId);
            }
            // 5.处理成功：手动确认
            channel.basicAck(deliveryTag, false);
        } catch (BizIllegalException | BadRequestException e) {
            // 业务异常：确认消费并记录日志，避免无限重试
            log.error("处理延迟关单消息出现业务异常，orderId={}，原因={}", orderId, e.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 系统异常：不确认，由Spring重试（max-attempts=3），耗尽后进入死信队列 trade.delay.dlx.queue
            log.error("处理延迟关单消息出现系统异常，orderId={}", orderId, e);
            throw e;
        }
    }
}
