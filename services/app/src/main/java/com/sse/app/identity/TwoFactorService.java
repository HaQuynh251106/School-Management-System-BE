package com.sse.app.identity;

import com.sse.app.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

@Service
public class TwoFactorService {
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final long PERIOD_SECONDS = 30;
    private final TwoFactorCredentialRepository repository;
    private final UserRepository users;
    private final byte[] encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public TwoFactorService(TwoFactorCredentialRepository repository, UserRepository users,
                            @Value("${sse.security.two-factor-key:}") String configuredKey,
                            @Value("${sse.jwt.secret}") String jwtSecret) {
        this.repository = repository;
        this.users = users;
        try {
            String keyMaterial = configuredKey == null || configuredKey.isBlank() ? jwtSecret : configuredKey;
            this.encryptionKey = MessageDigest.getInstance("SHA-256")
                    .digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Không thể khởi tạo mã hóa 2FA", e);
        }
    }

    public boolean isEnabled(String userId) {
        return repository.findById(userId).map(TwoFactorCredential::isEnabled).orElse(false);
    }

    @Transactional
    public IdentityDtos.TwoFactorSetup beginSetup(String userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Tài khoản"));
        byte[] raw = new byte[20];
        random.nextBytes(raw);
        String secret = encodeBase32(raw);
        TwoFactorCredential credential = repository.findById(userId).orElseGet(TwoFactorCredential::new);
        credential.setUserId(userId);
        credential.setSecretCiphertext(encrypt(secret));
        credential.setEnabled(false);
        credential.setCreatedAt(Instant.now());
        credential.setEnabledAt(null);
        credential.setLastUsedCounter(null);
        repository.save(credential);
        String label = url("Trường học số:" + user.getUsername());
        String issuer = url("Trường học số");
        return new IdentityDtos.TwoFactorSetup(secret,
                "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + issuer + "&digits=6&period=30");
    }

    @Transactional
    public void enable(String userId, String code) {
        TwoFactorCredential credential = repository.findById(userId)
                .orElseThrow(() -> ApiException.badRequest("Hãy tạo mã thiết lập 2FA trước"));
        long counter = verify(decrypt(credential.getSecretCiphertext()), code, false, null);
        credential.setEnabled(true);
        credential.setEnabledAt(Instant.now());
        credential.setLastUsedCounter(counter);
        repository.save(credential);
    }

    @Transactional
    public void disable(String userId, String code) {
        TwoFactorCredential credential = repository.findById(userId)
                .filter(TwoFactorCredential::isEnabled)
                .orElseThrow(() -> ApiException.badRequest("2FA chưa được bật"));
        verify(decrypt(credential.getSecretCiphertext()), code, false, credential.getLastUsedCounter());
        repository.delete(credential);
    }

    @Transactional
    public void verifyLogin(String userId, String code) {
        TwoFactorCredential credential = repository.findById(userId)
                .filter(TwoFactorCredential::isEnabled)
                .orElse(null);
        if (credential == null) return;
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "TWO_FACTOR_REQUIRED",
                    "Nhập mã xác thực 6 chữ số từ ứng dụng bảo mật");
        }
        long counter = verify(decrypt(credential.getSecretCiphertext()), code, true, credential.getLastUsedCounter());
        credential.setLastUsedCounter(counter);
        repository.save(credential);
    }

    private long verify(String secret, String code, boolean login, Long lastUsedCounter) {
        long now = Instant.now().getEpochSecond() / PERIOD_SECONDS;
        for (long counter = now - 1; counter <= now + 1; counter++) {
            if (totp(secret, counter).equals(code)) {
                if (login && lastUsedCounter != null && counter <= lastUsedCounter) {
                    throw new ApiException(HttpStatus.UNAUTHORIZED, "TWO_FACTOR_CODE_REUSED",
                            "Mã xác thực đã được sử dụng. Hãy chờ mã mới.");
                }
                return counter;
            }
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "TWO_FACTOR_INVALID", "Mã xác thực không đúng hoặc đã hết hạn");
    }

    private String totp(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể xác thực 2FA", e);
        }
    }

    private String encrypt(String value) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Không thể lưu bí mật 2FA", e);
        }
    }

    private String decrypt(String value) {
        try {
            byte[] combined = Base64.getDecoder().decode(value);
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể đọc bí mật 2FA", e);
        }
    }

    private String encodeBase32(byte[] input) {
        StringBuilder output = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                output.append(BASE32[(buffer >> (bitsLeft - 5)) & 31]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) output.append(BASE32[(buffer << (5 - bitsLeft)) & 31]);
        return output.toString();
    }

    private byte[] decodeBase32(String value) {
        ByteBuffer output = ByteBuffer.allocate(value.length() * 5 / 8 + 1);
        int buffer = 0, bitsLeft = 0;
        for (char c : value.toUpperCase(Locale.ROOT).toCharArray()) {
            int index = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            if (index < 0) continue;
            buffer = (buffer << 5) | index;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output.put((byte) ((buffer >> (bitsLeft - 8)) & 0xff));
                bitsLeft -= 8;
            }
        }
        byte[] bytes = new byte[output.position()];
        output.flip(); output.get(bytes);
        return bytes;
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
