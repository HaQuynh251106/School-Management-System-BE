CREATE TABLE notification_preferences (
    enabled boolean NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    channel varchar(255) NOT NULL,
    id varchar(255) NOT NULL,
    user_id varchar(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, channel)
);
CREATE INDEX idx_notification_pref_user ON notification_preferences (user_id);

CREATE TABLE notification_delivery_logs (
    attempts integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    channel varchar(255) NOT NULL,
    detail varchar(1000),
    id varchar(255) NOT NULL,
    notification_id varchar(255),
    recipient_id varchar(255) NOT NULL,
    status varchar(255) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_delivery_recipient ON notification_delivery_logs (recipient_id, created_at);
CREATE INDEX idx_delivery_notification ON notification_delivery_logs (notification_id);
