package com.xetax.crm.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Short-TTL Redis cache for the JWT filter's per-request user lookup.
 *
 * <p>Every authenticated request used to hit MySQL for the token's user —
 * this cache answers repeat lookups from Redis for {@link #TTL}, cutting one
 * DB query per request at high RPS. Only the fields the application actually
 * reads off the principal are cached (id, email, name, flags) — never the
 * password hash.
 *
 * <p>Redis being down never breaks a request: every cache operation degrades
 * silently to the plain database lookup. updateUser/deleteUser evict, so a
 * disabled or changed user is stale for at most {@link #TTL}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCacheService {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String PREFIX = "xetax:user:";

    private final AuthUserRepository userRepository;

    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper;

    public Optional<AuthUserEntity> findById(UUID userId) {
        String key = PREFIX + userId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return Optional.of(fromJson(cached));
            }
        } catch (Exception e) {
            log.debug("User cache read skipped: {}", e.getMessage());
        }

        Optional<AuthUserEntity> user = userRepository.findById(userId);
        user.ifPresent(u -> {
            try {
                redis.opsForValue().set(key, toJson(u), TTL);
            } catch (Exception e) {
                log.debug("User cache write skipped: {}", e.getMessage());
            }
        });
        return user;
    }

    public void evict(UUID userId) {
        try {
            redis.delete(PREFIX + userId);
        } catch (Exception e) {
            log.debug("User cache evict skipped: {}", e.getMessage());
        }
    }

    private String toJson(AuthUserEntity user) throws Exception {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("id", user.getId().toString());
        snapshot.put("email", user.getEmail());
        snapshot.put("name", user.getName());
        snapshot.put("enable", user.isEnabled());
        snapshot.put("admin", user.isAdmin());
        snapshot.put("company", user.getCompany());
        snapshot.put("phone", user.getPhone());
        snapshot.put("parentId", user.getParentId());
        snapshot.put("platformAdmin", Boolean.TRUE.equals(user.getPlatformAdmin()));
        return objectMapper.writeValueAsString(snapshot);
    }

    @SuppressWarnings("unchecked")
    private AuthUserEntity fromJson(String json) throws Exception {
        Map<String, Object> snapshot = objectMapper.readValue(json, Map.class);
        AuthUserEntity user = new AuthUserEntity();
        user.setId(UUID.fromString((String) snapshot.get("id")));
        user.setEmail((String) snapshot.get("email"));
        user.setName((String) snapshot.get("name"));
        user.setEnable(Boolean.TRUE.equals(snapshot.get("enable")));
        user.setAdmin(Boolean.TRUE.equals(snapshot.get("admin")));
        user.setCompany((String) snapshot.get("company"));
        user.setPhone((String) snapshot.get("phone"));
        user.setParentId((String) snapshot.get("parentId"));
        user.setPlatformAdmin(Boolean.TRUE.equals(snapshot.get("platformAdmin")));
        return user;
    }
}
