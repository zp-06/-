package com.hmall.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnClass(RabbitTemplate.class)
public class mqConfig {
    @Bean
    public MessageConverter messageConverter(){
        // 1.定义消息转换器
        Jackson2JsonMessageConverter jackson2JsonMessageConverter = new Jackson2JsonMessageConverter();
        // 2.配置自动创建消息id，用于识别不同消息，也可以在业务中基于ID判断是否是重复消息
        jackson2JsonMessageConverter.setCreateMessageIds(true);
        return jackson2JsonMessageConverter;
    }

    /**
     * 定制RabbitTemplate：统一处理生产者确认（ConfirmCallback）与消息路由失败回调（ReturnsCallback）。
     * 依赖 application.yaml 中的 publisher-confirm-type: correlated、publisher-returns: true。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter converter){
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        // 开启 mandatory：消息无法路由到任何队列时触发 ReturnsCallback
        template.setMandatory(true);
        // 生产者确认回调：消息到达交换机后 broker 会 ack/nack
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("MQ消息发送失败(生产者确认nack)，correlationId={}，原因={}",
                        correlationData == null ? null : correlationData.getId(), cause);
            } else {
                log.debug("MQ消息发送成功，correlationId={}", correlationData == null ? null : correlationData.getId());
            }
        });
        // 路由失败回调：消息没有路由到任何队列（交换机存在但无匹配的绑定/路由键）
        template.setReturnsCallback(returned -> {
            log.error("MQ消息路由失败：exchange={}，routingKey={}，replyCode={}，replyText={}，消息={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText(), returned.getMessage());
        });
        return template;
    }
}
