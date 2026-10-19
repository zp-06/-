package com.hmall.item.config;

import com.hmall.common.constants.MQConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 死信配置：商品变更->ES同步队列（item.es.sync.queue）消费失败、重试耗尽后的死信交换机与队列。
 */
@Configuration
public class ItemEsMqConfig {

    @Bean
    public DirectExchange itemEsDlxExchange() {
        return new DirectExchange(MQConstants.ITEM_ES_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue itemEsDlxQueue() {
        return QueueBuilder.durable(MQConstants.ITEM_ES_DLX_QUEUE).build();
    }

    @Bean
    public Binding itemEsDlxBinding() {
        return BindingBuilder.bind(itemEsDlxQueue()).to(itemEsDlxExchange()).with(MQConstants.ITEM_ES_KEY);
    }
}
