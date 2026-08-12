package com.sse.app.identity;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
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
                               @Value("${sse.mail.reset-url:http://localhost:5173/#/dat-lai-mat-khau}") String resetUrl,
                               @Value("${sse.mail.activation-url:http://localhost:5173/#/kich-hoat-tai-khoan}") String activationUrl) {
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
        String link = resetUrl + "?token=" + token;
        return sendHtml(recipient, "Đặt lại mật khẩu | Trường học số",
                emailLayout("Đặt lại mật khẩu",
                        "Chúng tôi đã nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.",
                        "Đặt lại mật khẩu", link,
                        "Liên kết có hiệu lực trong 30 phút và chỉ sử dụng được một lần.", null));
    }

    public boolean sendActivation(String recipient, String token, String username, String fullName, String role) {
        String link = activationUrl + "?activationToken=" + token;
        String account = "<table role=\"presentation\" style=\"width:100%;margin:20px 0;border-collapse:collapse;background:#f8fafc;border:1px solid #e2e8f0;border-radius:12px\">"
                + "<tr><td style=\"padding:14px 16px;color:#64748b;width:130px\">Người dùng</td><td style=\"padding:14px 16px;font-weight:700;color:#0f172a\">" + escape(fullName) + "</td></tr>"
                + "<tr><td style=\"padding:14px 16px;color:#64748b;border-top:1px solid #e2e8f0\">Tên đăng nhập</td><td style=\"padding:14px 16px;font-weight:700;color:#2563eb;border-top:1px solid #e2e8f0\">" + escape(username) + "</td></tr>"
                + "<tr><td style=\"padding:14px 16px;color:#64748b;border-top:1px solid #e2e8f0\">Vai trò</td><td style=\"padding:14px 16px;font-weight:600;color:#0f172a;border-top:1px solid #e2e8f0\">" + escape(roleLabel(role)) + "</td></tr></table>";
        return sendHtml(recipient, "Kích hoạt tài khoản | Trường học số",
                emailLayout("Chào mừng bạn đến với Trường học số",
                        "Nhà trường đã tạo tài khoản dành cho bạn.",
                        "Kích hoạt và tạo mật khẩu", link,
                        "Liên kết có hiệu lực trong 48 giờ và chỉ sử dụng được một lần.", account));
    }

    /**
     * Tạo/import tài khoản không được chờ SMTP hoàn tất. Email được đưa sang
     * executor thông báo; thao tác gửi lại thủ công vẫn dùng hàm đồng bộ ở trên
     * để giao diện có thể báo chính xác kết quả cho quản trị viên.
     */
    @Async("notificationExecutor")
    public void sendActivationAsync(String recipient, String token, String username, String fullName, String role) {
        sendActivation(recipient, token, username, fullName, role);
    }

    private boolean sendHtml(String recipient, String subject, String body) {
        if (!enabled || recipient == null || recipient.isBlank()) {
            log.info("Account email delivery skipped because mail is disabled or the account has no email");
            return false;
        }
        // RFC 2606 reserves .test/.example/.invalid for documentation and test data.
        // Never make a real SMTP connection for those domains during bulk imports.
        String normalizedRecipient = recipient.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalizedRecipient.endsWith(".test")
                || normalizedRecipient.endsWith(".example")
                || normalizedRecipient.endsWith(".invalid")) {
            log.info("Account email delivery skipped for reserved test recipient {}", recipient);
            return false;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, true);
            sender.send(message);
            return true;
        } catch (MailException | MessagingException e) {
            log.error("Account email delivery failed", e);
            return false;
        }
    }

    private String emailLayout(String title, String intro, String action, String link, String expiry, String details) {
        return "<!doctype html><html><body style=\"margin:0;background:#f1f5f9;font-family:Arial,sans-serif;color:#0f172a\">"
                + "<div style=\"display:none;max-height:0;overflow:hidden\">" + escape(intro) + "</div>"
                + "<table role=\"presentation\" width=\"100%\" style=\"background:#f1f5f9;padding:32px 12px\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" style=\"max-width:620px;background:#ffffff;border-radius:18px;overflow:hidden;box-shadow:0 10px 30px rgba(15,23,42,.08)\">"
                + "<tr><td style=\"padding:24px 30px;background:#1d4ed8;color:white\"><div style=\"font-size:13px;letter-spacing:.08em;text-transform:uppercase;opacity:.85\">Trường học số</div><div style=\"font-size:24px;font-weight:800;margin-top:6px\">" + escape(title) + "</div></td></tr>"
                + "<tr><td style=\"padding:30px\"><p style=\"font-size:16px;line-height:1.65;margin:0 0 12px\">" + escape(intro) + "</p>"
                + (details == null ? "" : details)
                + "<p style=\"margin:26px 0;text-align:center\"><a href=\"" + escape(link) + "\" style=\"display:inline-block;background:#2563eb;color:#fff;text-decoration:none;font-weight:700;padding:14px 24px;border-radius:10px\">" + escape(action) + "</a></p>"
                + "<div style=\"padding:14px 16px;background:#fff7ed;border:1px solid #fed7aa;border-radius:10px;color:#9a3412;font-size:13px;line-height:1.55\">🔒 " + escape(expiry) + " Không chia sẻ email hoặc liên kết này cho người khác.</div>"
                + "<p style=\"font-size:12px;color:#64748b;line-height:1.55;margin:22px 0 0\">Nếu nút không hoạt động, sao chép liên kết sau vào trình duyệt:<br><a href=\"" + escape(link) + "\" style=\"color:#2563eb;word-break:break-all\">" + escape(link) + "</a></p>"
                + "<p style=\"font-size:12px;color:#94a3b8;margin:24px 0 0\">Nếu bạn không mong đợi email này, vui lòng liên hệ quản trị viên nhà trường.</p></td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "ACADEMIC_STAFF" -> "Giáo vụ";
            case "ACCOUNTANT" -> "Kế toán";
            case "TEACHER" -> "Giáo viên";
            case "STUDENT" -> "Học sinh";
            case "PARENT" -> "Phụ huynh";
            case "ADMIN" -> "Quản trị viên";
            default -> "Người dùng";
        };
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
