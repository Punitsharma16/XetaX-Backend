package com.xetax.crm.data_manager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis cache: formId → ownerUserId.
 *
 * <p>The OwnershipGuard consults this on every field/stage/record request,
 * so a hot form's ownership check stops hitting MySQL entirely. A form's
 * owner never changes after creation, so the only eviction needed is on
 * form DELETE; the TTL is just a safety net.
 *
 * <p>Redis being down never breaks a request — callers fall back to the
 * database on any cache error or miss.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormOwnerCache {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String PREFIX = "xetax:form:owner:";

    private final StringRedisTemplate redis;

    public Optional<String> getOwner(Long formId) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(PREFIX + formId));
        } catch (Exception e) {
            log.debug("Form-owner cache read skipped: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void putOwner(Long formId, String ownerUserId) {
        if (ownerUserId == null) return;
        try {
            redis.opsForValue().set(PREFIX + formId, ownerUserId, TTL);
        } catch (Exception e) {
            log.debug("Form-owner cache write skipped: {}", e.getMessage());
        }
    }

    public void evict(Long formId) {
        try {
            redis.delete(PREFIX + formId);
        } catch (Exception e) {
            log.debug("Form-owner cache evict skipped: {}", e.getMessage());
        }
    }
}
