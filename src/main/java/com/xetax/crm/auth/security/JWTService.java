package com.xetax.crm.auth.security;

import com.xetax.crm.auth.user.AuthUserEntity;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and verifies the same HS256 tokens the standalone auth service and
 * gateway used — secret and issuer defaults are identical, so tokens issued
 * before the merge keep working.
 */
@Service
@Getter
@Setter
public class JWTService {

    private final SecretKey secretKey;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;
    private final String issuer;

    public JWTService(@Value("${security.jwt.secret}") String secretKey,
                      @Value("${security.jwt.access-ttl-seconds}") long accessTtlSeconds,
                      @Value("${security.jwt.refresh-ttl-seconds}") long refreshTtlSeconds,
                      @Value("${security.jwt.issuer}") String issuer) {
        // HS256 needs at least 256 bits of key material.
        if (secretKey == null || secretKey.length() < 32) {
            throw new IllegalArgumentException("Secret key must be at least 32 characters");
        }
        this.secretKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
        this.issuer = issuer;
    }

    public String generateAccessToken(AuthUserEntity user) {
        Instant now = Instant.now();
        return Jwts.builder().id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(java.util.Date.from(now.plusSeconds(accessTtlSeconds)))
                .claims(Map.of("user", user.getEmail(), "typ", "access"))
                .signWith(secretKey, SignatureAlgorithm.HS256).compact();
    }

    public String generateRefreshToken(AuthUserEntity user, String jti) {
        Instant now = Instant.now();
        return Jwts.builder().id(jti)
                .subject(user.getId().toString())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(java.util.Date.from(now.plusSeconds(accessTtlSeconds)))
                .claims(Map.of("typ", "refresh"))
                .signWith(secretKey, SignatureAlgorithm.HS256).compact();
    }

    public Jws<Claims> parse(String token) {
        try {
            return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isAccessToken(String token) {
        Claims claims = parse(token).getPayload();
        return "access".equals(claims.get("typ"));
    }

    public boolean isRefreshToken(String token) {
        Claims claims = parse(token).getPayload();
        return "refresh".equals(claims.get("typ"));
    }

    public UUID getUserId(String token) {
        Claims claims = parse(token).getPayload();
        return UUID.fromString(claims.getSubject());
    }

    public String getJti(String token) {
        return parse(token).getPayload().getId();
    }

    public List<String> getRoles(String token) {
        Claims claims = parse(token).getPayload();
        return (List<String>) claims.get("roles");
    }

    public String getemail(String token) {
        Claims claims = parse(token).getPayload();
        return claims.get("email").toString();
    }
}
