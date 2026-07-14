package com.sse.app.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetMailer {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);
    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    private final String resetUrl;

    public PasswordResetMailer(JavaMailSender sender,
                               @Value("${sse.mail.enabled:false}") boolean enabled,
                               @Value("${sse.mail.from:no-reply@smartschool.local}") String from,
                               @Value("${sse.mail.reset-url:http://localhost:5173/reset-password}") String resetUrl) {
        this.sender = sender;
        this.enabled = enabled;
        this.from = from;
        this.resetUrl = resetUrl;
    }

    public void send(String recipient, String token) {
        if (!enabled || recipient == null || recipient.isBlank()) {
            log.info("Password reset email delivery skipped because mail is disabled or the account has no email");
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject("Đặt lại mật khẩu Smart School");
        message.setText("Liên kết đặt lại mật khẩu (hết hạn sau 30 phút):\n" + resetUrl + "?token=" + token);
        try {
            sender.send(message);
        } catch (MailException e) {
            log.error("Password reset email delivery failed", e);
        }
    }
}
