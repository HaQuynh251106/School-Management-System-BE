package com.sse.app.notification;

import com.sse.app.event.DomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "sse.notifications.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitNotificationWorker {
    private final NotificationService notifications;

    public RabbitNotificationWorker(NotificationService notifications) {
        this.notifications = notifications;
    }

    @RabbitListener(queues = "${sse.rabbitmq.notification-queue:sse.notification.events}")
    public void consume(DomainEvent event,
                        @Header(name = AmqpHeaders.RECEIVED_ROUTING_KEY, required = false) String routingKey) {
        log.info("Consuming notification event {} via routingKey={}", event.name(), routingKey);
        notifications.handleDomainEvent(event);
    }
}
