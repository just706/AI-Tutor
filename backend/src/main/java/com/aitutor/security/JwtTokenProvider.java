package com.aitutor.security;

import com.aitutor.entity.User;
import com.aitutor.exception.UnauthorizedException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwtTokenProvider {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;

    public JwtTokenProvider(JwtProperties jwtProperties, ObjectMapper objectMapper) {
        this.jwtProperties = jwtProperties;
        this.objectMapper = objectMapper;
    }

    public String generateToken(User user) {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + Duration.ofMinutes(jwtProperties.getExpireMinutes()).getSeconds();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", jwtProperties.getIssuer());
        payload.put("sub", String.valueOf(user.getId()));
        payload.put("uid", user.getId());
        payload.put("username", user.getUsername());
        payload.put("role", user.getRole());
        payload.put("iat", issuedAt);
        payload.put("exp", expiresAt);

        String signingInput = encodeJson(header) + "." + encodeJson(payload);
        return signingInput + "." + sign(signingInput);
    }

    public CurrentUser parseToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new UnauthorizedException("未登录或 Token 无效");
            }

            String signingInput = parts[0] + "." + parts[1];
            String expectedSignature = sign(signingInput);
            if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                throw new UnauthorizedException("未登录或 Token 无效");
            }

            Map<String, Object> header = decodeJson(parts[0]);
            if (!"HS256".equals(header.get("alg"))) {
                throw new UnauthorizedException("未登录或 Token 无效");
            }

            Map<String, Object> payload = decodeJson(parts[1]);
            if (!jwtProperties.getIssuer().equals(payload.get("iss"))) {
                throw new UnauthorizedException("未登录或 Token 无效");
            }

            long expiresAt = readLong(payload.get("exp"));
            if (expiresAt < Instant.now().getEpochSecond()) {
                throw new UnauthorizedException("Token 已过期");
            }

            Long userId = readLong(payload.get("uid"));
            String username = readString(payload.get("username"));
            String role = readString(payload.get("role"));
            return new CurrentUser(userId, username, role);
        } catch (UnauthorizedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new UnauthorizedException("未登录或 Token 无效");
        }
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(value);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception ex) {
            throw new IllegalStateException("JWT 生成失败", ex);
        }
    }

    private Map<String, Object> decodeJson(String value) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(value);
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new UnauthorizedException("未登录或 Token 无效");
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception ex) {
            throw new IllegalStateException("JWT 签名失败", ex);
        }
    }

    private Long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            return Long.parseLong(stringValue);
        }
        throw new UnauthorizedException("未登录或 Token 无效");
    }

    private String readString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }
}
