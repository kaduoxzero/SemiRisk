package com.semirisk.security;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class CsrfTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] secret = new byte[32];

    public CsrfTokenService() {
        RANDOM.nextBytes(secret);
    }

    public String issue() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String issuedAt = String.valueOf(Instant.now().getEpochSecond());
        String nonceText = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String payload = issuedAt + ":" + nonceText;
        return payload + ":" + sign(payload);
    }

    public boolean validate(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String[] parts = token.split(":");
        if (parts.length != 3) {
            return false;
        }
        try {
            long issuedAt = Long.parseLong(parts[0]);
            long ageSeconds = Instant.now().getEpochSecond() - issuedAt;
            if (ageSeconds < 0 || ageSeconds > 30 * 60) {
                return false;
            }
            String payload = parts[0] + ":" + parts[1];
            return MessageDigest.isEqual(sign(payload).getBytes(), parts[2].getBytes());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes()));
        } catch (Exception ex) {
            throw new IllegalStateException("CSRF Token 签名失败", ex);
        }
    }
}
