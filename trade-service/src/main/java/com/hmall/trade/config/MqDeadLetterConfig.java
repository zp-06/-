package com.hmall.trade.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 死信配置：
 * 消费者手动确认 + 重试（max-attempts=3）仍失败的消息，会被拒收（requeue=false），
 * 进入这里声明的死信交换机（DLX），由死信队列（DLQ）暂存，便于人工排查/补偿。
 */
@Configuration
public class MqDeadLetterConfig {

    /* ==================== 支付成功队列（trade.pay.success.queue）的死信 ==================== */

    @Bean
    public DirectExchange payDlxExchange() {
        return new DirectExchange("trade.pay.dlx.exchange", true, false);
    }

    @Bean
    public Queue payDlxQueue() {
        return QueueBuilder.durable("trade.pay.dlx.queue").build();
    }

    @Bean
    public Binding payDlxBinding() {
        return BindingBuilder.bind(payDlxQueue()).to(payDlxExchange()).with("pay.success");
    }

    /* ==================== 延迟关单队列（trade.delay.order.queue）的死信 ==================== */

    @Bean
    public DirectExchange delayDlxExchange() {
        return new DirectExchange("trade.delay.dlx.exchange", true, false);
    }

    @Bean
    public Queue delayDlxQueue() {
        return QueueBuilder.durable("trade.delay.dlx.queue").build();
    }

    @Bean
    public Binding delayDlxBinding() {
        return BindingBuilder.bind(delayDlxQueue()).to(delayDlxExchange()).with("delay.order.query");
    }
}
