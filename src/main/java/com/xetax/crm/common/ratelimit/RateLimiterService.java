package com.xetax.crm.common.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis fixed-window rate limiter.
 *
 * <p>One INCR per call; the first hit of a window sets the expiry. Keys look
 * like {@code xetax:rl:login:1.2.3.4} and disappear on their own after the
 * window.
 *
 * <p>FAIL-OPEN by design: if Redis is unavailable the request is allowed —
 * a cache outage must never take the API down. Callers use
 * {@link #check(String, int, Duration)} which throws
 * {@link RateLimitExceededException} (mapped to HTTP 429) when the caller is
 * over the limit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private static final String PREFIX = "xetax:rl:";

    private final StringRedisTemplate redis;

    public void check(String key, int limit, Duration window) {
        if (!allow(key, limit, window)) {
            throw new RateLimitExceededException(
                    "Too many requests. Please try again in a little while.");
        }
    }

    public boolean allow(String key, int limit, Duration window) {
        try {
            String redisKey = PREFIX + key;
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, window);
            }
            return count == null || count <= limit;
        } catch (Exception e) {
            log.debug("Rate limiter skipped (Redis unavailable): {}", e.getMessage());
            return true;
        }
    }
}
