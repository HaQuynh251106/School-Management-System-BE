package com.sse.app.notification;

import com.sse.app.event.DomainEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Local fallback bridge for dev/test when RabbitMQ is explicitly disabled.
 * The normal path is RabbitNotificationWorker consuming from RabbitMQ.
 */
@Component
@ConditionalOnProperty(prefix = "sse.events.local-listener", name = "enabled", havingValue = "true")
public class NotificationEventListener {
    private final NotificationService notifications;

    public NotificationEventListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Async
    @EventListener
    public void onDomainEvent(DomainEvent event) {
        notifications.handleDomainEvent(event);
    }
}
