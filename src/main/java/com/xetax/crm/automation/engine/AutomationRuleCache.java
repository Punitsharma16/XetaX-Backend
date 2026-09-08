package com.xetax.crm.automation.engine;

import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.automation.repository.AutomationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis presence-cache for automation rules: "does form X have any ACTIVE
 * rule for trigger Y?"
 *
 * <p>The engine runs on EVERY record create/update/stage-change, and most
 * forms have no rules at all — this cache lets the engine skip the rules
 * query entirely in that common case. When rules DO exist the engine still
 * loads them fresh from the database, so execution fidelity (lazy fields,
 * conditions, ordering) is untouched.
 *
 * <p>Evicted per form on any automation create/update/delete; the TTL is a
 * safety net. Redis down → treated as "might have rules" (fail-safe: the
 * engine just does what it always did).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationRuleCache {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String PREFIX = "xetax:auto:has:";

    private final AutomationRepository automationRepository;

    private final StringRedisTemplate redis;

    /** False ONLY when we know for sure the form has no active rules. */
    public boolean mayHaveRules(Long formId, AutomationTrigger trigger) {
        String key = PREFIX + formId + ":" + trigger;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return "1".equals(cached);
            }
        } catch (Exception e) {
            log.debug("Rule cache read skipped: {}", e.getMessage());
            return true;
        }
        boolean has = !automationRepository.findByFormIdAndTriggerAndActiveTrue(formId, trigger).isEmpty();
        try {
            redis.opsForValue().set(key, has ? "1" : "0", TTL);
        } catch (Exception e) {
            log.debug("Rule cache write skipped: {}", e.getMessage());
        }
        return has;
    }

    /** Evicts all trigger keys of the form — called on any rule change. */
    public void evict(Long formId) {
        try {
            for (AutomationTrigger trigger : AutomationTrigger.values()) {
                redis.delete(PREFIX + formId + ":" + trigger);
            }
        } catch (Exception e) {
            log.debug("Rule cache evict skipped: {}", e.getMessage());
        }
    }
}
