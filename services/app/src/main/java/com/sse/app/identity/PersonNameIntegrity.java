package com.sse.app.identity;

import com.sse.app.common.ApiException;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Normalizes person names and rejects irreversible UTF-8 damage markers. */
public final class PersonNameIntegrity {
    private static final Pattern MOJIBAKE = Pattern.compile("(?:Ã|Ä|Å|Æ|áº|á»|â€|ðŸ)");

    private PersonNameIntegrity() {}

    public static String required(String value) {
        String normalized = optional(value);
        if (normalized == null) throw ApiException.badRequest("Họ tên không được để trống");
        return normalized;
    }

    public static String optional(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = Normalizer.normalize(value.trim().replaceAll("\\s+", " "), Normalizer.Form.NFC);
        if (normalized.indexOf('?') >= 0
                || normalized.indexOf('\uFFFD') >= 0
                || MOJIBAKE.matcher(normalized).find()) {
            throw ApiException.badRequest("Họ tên có ký tự lỗi mã hóa. Vui lòng lưu tệp và nhập lại bằng Unicode UTF-8");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("Họ tên chứa ký tự điều khiển không hợp lệ");
        }
        return normalized;
    }
}
