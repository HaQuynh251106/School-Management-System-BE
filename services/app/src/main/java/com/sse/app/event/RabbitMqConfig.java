package com.sse.app.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
@ConditionalOnProperty(prefix = "sse.events.rabbitmq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    @Bean
    public MessageConverter rabbitJsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter rabbitJsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(rabbitJsonMessageConverter);
        return template;
    }

    @Bean
    public Declarables sseEventTopology(
            @Value("${sse.rabbitmq.exchange:sse.events}") String exchangeName,
            @Value("${sse.rabbitmq.dead-letter-exchange:sse.events.dlx}") String deadLetterExchangeName,
            @Value("${sse.rabbitmq.notification-queue:sse.notification.events}") String notificationQueueName,
            @Value("${sse.rabbitmq.notification-dead-letter-queue:sse.notification.events.dlq}") String notificationDeadLetterQueueName,
            @Value("${sse.rabbitmq.notification-dead-letter-routing-key:notification.failed}") String deadLetterRoutingKey) {

        TopicExchange eventExchange = new TopicExchange(exchangeName, true, false);
        DirectExchange deadLetterExchange = new DirectExchange(deadLetterExchangeName, true, false);
        Queue notificationQueue = QueueBuilder.durable(notificationQueueName)
                .withArgument("x-dead-letter-exchange", deadLetterExchangeName)
                .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(notificationDeadLetterQueueName).build();

        Binding allEventsToNotifications = BindingBuilder.bind(notificationQueue).to(eventExchange).with("#");
        Binding failedNotifications = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(deadLetterRoutingKey);

        return new Declarables(eventExchange, deadLetterExchange, notificationQueue, deadLetterQueue,
                allEventsToNotifications, failedNotifications);
    }
}
