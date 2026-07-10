package com.sse.app.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class DomainEventPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final ApplicationEventPublisher localPublisher;
    private final boolean rabbitEnabled;
    private final String exchange;

    public DomainEventPublisher(RabbitTemplate rabbitTemplate,
                                ApplicationEventPublisher localPublisher,
                                @Value("${sse.events.rabbitmq.enabled:true}") boolean rabbitEnabled,
                                @Value("${sse.rabbitmq.exchange:sse.events}") String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.localPublisher = localPublisher;
        this.rabbitEnabled = rabbitEnabled;
        this.exchange = exchange;
    }

    public void publish(String name, String actorUserId, String entityType,
                        String entityId, Map<String, Object> payload) {
        DomainEvent event = DomainEvent.of(name, actorUserId, entityType, entityId, payload);
        if (!rabbitEnabled) {
            localPublisher.publishEvent(event);
            return;
        }

        try {
            rabbitTemplate.convertAndSend(exchange, name, event);
        } catch (AmqpException ex) {
            // Core API must not fail because the notification bus is temporarily unavailable.
            log.warn("RabbitMQ publish failed for event {} entity={}/{}: {}",
                    name, entityType, entityId, ex.getMessage());
        }
    }
}
