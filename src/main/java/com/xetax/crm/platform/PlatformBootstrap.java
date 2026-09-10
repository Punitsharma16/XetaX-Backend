package com.xetax.crm.platform;

import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.billing.AiQuotaService;
import com.xetax.crm.billing.OrgPlan;
import com.xetax.crm.billing.OrgPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Makes the first platform admin without anyone touching SQL: list the emails
 * in PLATFORM_ADMIN_EMAILS and they are promoted on every boot, and their own
 * workspace is put on the PLATFORM plan (no subscription row, so nothing ever
 * expires it). Removing an email from the list does not demote anyone — that is
 * deliberate, so a typo cannot lock the team out of its own console.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformBootstrap {

    private final AuthUserRepository users;
    private final OrgPlanRepository plans;
    private final AiQuotaService quotaService;

    @Value("${app.platform-admin-emails:}")
    private String emails;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void promote() {
        if (emails == null || emails.isBlank()) return;
        for (String raw : emails.split(",")) {
            String email = raw.trim();
            if (email.isEmpty()) continue;
            users.findByEmail(email).ifPresentOrElse(user -> {
                if (!Boolean.TRUE.equals(user.getPlatformAdmin())) {
                    user.setPlatformAdmin(true);
                    users.save(user);
                    log.info("Platform admin granted to {}", email);
                }
                try {
                    OrgPlan plan = quotaService.planOf(user.getId().toString());
                    if (!"PLATFORM".equals(plan.getPlanKey())) {
                        plan.setPlanKey("PLATFORM");
                        plan.setUpdatedAt(LocalDateTime.now());
                        plans.save(plan);
                    }
                } catch (Exception e) {
                    log.warn("Could not put {} on the PLATFORM plan: {}", email, e.getMessage());
                }
            }, () -> log.warn("PLATFORM_ADMIN_EMAILS lists {}, but no such account exists yet", email));
        }
    }
}
