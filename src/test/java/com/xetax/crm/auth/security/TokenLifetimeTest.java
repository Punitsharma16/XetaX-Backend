package com.xetax.crm.auth.security;

import com.xetax.crm.auth.user.AuthUserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Keep me signed in" rests on the refresh token outliving the access token.
 * Stamped with the access token's hour, it expired at the same second: the
 * silent refresh could never succeed, and everyone was thrown back to the
 * login page an hour into their work.
 */
class TokenLifetimeTest {

    private static final long ACCESS_TTL = 3600;
    private static final long REFRESH_TTL = 86_400;

    private JWTService jwt;
    private AuthUserEntity user;

    @BeforeEach
    void setUp() {
        jwt = new JWTService("a-test-secret-that-is-long-enough-for-hs256",
                ACCESS_TTL, REFRESH_TTL, "xetax-test");
        user = new AuthUserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("qa@xetacrm.pro");
    }

    private long secondsOfLife(String token) {
        var claims = jwt.parse(token).getPayload();
        return (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
    }

    @Test
    void theRefreshTokenOutlivesTheAccessToken() {
        long access = secondsOfLife(jwt.generateAccessToken(user));
        long refresh = secondsOfLife(jwt.generateRefreshToken(user, UUID.randomUUID().toString()));

        assertTrue(refresh > access, "refresh " + refresh + "s vs access " + access + "s");
    }

    @Test
    void eachTokenGetsTheLifetimeItIsConfiguredWith() {
        assertEquals(ACCESS_TTL, secondsOfLife(jwt.generateAccessToken(user)));
        assertEquals(REFRESH_TTL, secondsOfLife(jwt.generateRefreshToken(user, "jti-1")));
    }

    @Test
    void theTwoTokensStaySeparateKinds() {
        assertTrue(jwt.isAccessToken(jwt.generateAccessToken(user)));
        assertTrue(!jwt.isAccessToken(jwt.generateRefreshToken(user, "jti-2")));
    }
}
