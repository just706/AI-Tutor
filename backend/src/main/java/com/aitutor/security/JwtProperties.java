package com.aitutor.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret = "dev-only-ai-tutor-jwt-secret-change-me";
    private String issuer = "ai-tutor";
    private Long expireMinutes = 120L;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Long getExpireMinutes() {
        return expireMinutes;
    }

    public void setExpireMinutes(Long expireMinutes) {
        this.expireMinutes = expireMinutes;
    }
}
