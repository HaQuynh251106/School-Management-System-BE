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
    private final String activationUrl;

    public PasswordResetMailer(JavaMailSender sender,
                               @Value("${sse.mail.enabled:false}") boolean enabled,
                               @Value("${sse.mail.from:no-reply@smartschool.local}") String from,
                               @Value("${sse.mail.reset-url:http://localhost:5173/reset-password}") String resetUrl,
                               @Value("${sse.mail.activation-url:http://localhost:5173/activate-account}") String activationUrl) {
        this.sender = sender;
        this.enabled = enabled;
        this.from = from;
        this.resetUrl = resetUrl;
        this.activationUrl = activationUrl;
    }

    public boolean send(String recipient, String token) {
        return sendReset(recipient, token);
    }

    public boolean sendReset(String recipient, String token) {
        return sendMessage(recipient, "Đặt lại mật khẩu Trường học số",
                "Liên kết đặt lại mật khẩu (hết hạn sau 30 phút):\n" + resetUrl + "?token=" + token);
    }

    public boolean sendActivation(String recipient, String token) {
        return sendMessage(recipient, "Kích hoạt tài khoản Trường học số",
                "Tài khoản của bạn đã được nhà trường tạo. Hãy kích hoạt và tự đặt mật khẩu trong 48 giờ:\n"
                        + activationUrl + "?activationToken=" + token);
    }

    private boolean sendMessage(String recipient, String subject, String body) {
        if (!enabled || recipient == null || recipient.isBlank()) {
            log.info("Account email delivery skipped because mail is disabled or the account has no email");
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
            return true;
        } catch (MailException e) {
            log.error("Account email delivery failed", e);
            return false;
        }
    }
}
