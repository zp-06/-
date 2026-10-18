package com.hmall.cart.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 死信配置：购物车清空队列（cart.clear.queue）消费失败、重试耗尽后的死信交换机与队列。
 */
@Configuration
public class CartMqConfig {

    @Bean
    public DirectExchange cartDlxExchange() {
        return new DirectExchange("cart.dlx.exchange", true, false);
    }

    @Bean
    public Queue cartDlxQueue() {
        return QueueBuilder.durable("cart.dlx.queue").build();
    }

    @Bean
    public Binding cartDlxBinding() {
        return BindingBuilder.bind(cartDlxQueue()).to(cartDlxExchange()).with("order.create");
    }
}
