package com.xetax.crm.billing;

import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.settings.service.OrgSmtpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Paid plan periods. Activating a subscription flips {@code org_plans.plan_key}
 * so every existing quota/limit check keeps working untouched; when the period
 * ends the org drops back to TRIAL (already past its trial window, so it
 * behaves like the free tier) and the owner is told by bell + email.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final OrgSubscriptionRepository subscriptionRepository;
    private final OrgPlanRepository planRepository;
    private final AiQuotaService quotaService;
    private final NotificationService notificationService;
    private final OrgSmtpService orgSmtpService;
    private final AuthUserRepository authUserRepository;

    /** Start a paid period. Any previous ACTIVE subscription is superseded. */
    @Transactional
    public OrgSubscription activate(String ownerUserId, String planKey, int months,
                                    long amountPaise, String paymentRef, String note) {
        String key = planKey == null ? "" : planKey.trim().toUpperCase();
        if ("TRIAL".equals(key) || !PlanCatalog.PLANS.containsKey(key)) {
            throw new BadRequestException("Unknown plan: " + planKey);
        }
        if (months < 1 || months > 36) throw new BadRequestException("months must be 1-36");

        subscriptionRepository.findFirstByOwnerUserIdAndStatusOrderByIdDesc(ownerUserId, "ACTIVE")
                .ifPresent(old -> {
                    old.setStatus("CANCELLED");
                    old.setNote(prefixNote(old.getNote(), "Replaced by " + key));
                    old.setUpdatedAt(LocalDateTime.now());
                    subscriptionRepository.save(old);
                });

        LocalDateTime now = LocalDateTime.now();
        OrgSubscription sub = subscriptionRepository.save(OrgSubscription.builder()
                .ownerUserId(ownerUserId)
                .planKey(key)
                .status("ACTIVE")
                .startsAt(now)
                .endsAt(now.plusMonths(months))
                .amountPaise(Math.max(0, amountPaise))
                .paymentRef(paymentRef)
                .note(note)
                .renewalReminderSent(false)
                .createdAt(now)
                .updatedAt(now)
                .build());

        OrgPlan plan = quotaService.planOf(ownerUserId);
        plan.setPlanKey(key);
        plan.setUpdatedAt(now);
        planRepository.save(plan);

        log.info("Subscription #{} activated: {} on {} for {} month(s)", sub.getId(), ownerUserId, key, months);
        return sub;
    }

    /**
     * The org's current ACTIVE subscription, expiring it on the spot when its
     * period has already ended — so callers never see a stale ACTIVE row.
     */
    @Transactional
    public Optional<OrgSubscription> currentFor(String ownerUserId) {
        Optional<OrgSubscription> current = subscriptionRepository
                .findFirstByOwnerUserIdAndStatusOrderByIdDesc(ownerUserId, "ACTIVE");
        if (current.isPresent() && current.get().getEndsAt().isBefore(LocalDateTime.now())) {
            expire(current.get());
            return Optional.empty();
        }
        return current;
    }

    public List<OrgSubscription> history(String ownerUserId) {
        return subscriptionRepository.findTop20ByOwnerUserIdOrderByIdDesc(ownerUserId);
    }

    /** Daily sweep: send 7-day renewal reminders, expire finished periods. */
    @Scheduled(cron = "0 20 4 * * *")
    @Transactional
    public void dailySweep() {
        LocalDateTime now = LocalDateTime.now();
        for (OrgSubscription sub : subscriptionRepository.findByStatus("ACTIVE")) {
            try {
                if (sub.getEndsAt().isBefore(now)) {
                    expire(sub);
                } else if (!sub.isRenewalReminderSent() && sub.getEndsAt().isBefore(now.plusDays(7))) {
                    sub.setRenewalReminderSent(true);
                    sub.setUpdatedAt(now);
                    subscriptionRepository.save(sub);
                    notifyOwner(sub.getOwnerUserId(),
                            "Your " + planLabel(sub) + " plan expires on " + sub.getEndsAt().format(DATE),
                            "Contact us to renew and avoid an interruption.",
                            "XetaX: your plan expires on " + sub.getEndsAt().format(DATE),
                            "Hi,\n\nYour " + planLabel(sub) + " plan ends on "
                                    + sub.getEndsAt().format(DATE) + ". Renew before then to keep your "
                                    + "quotas and limits — after expiry the workspace drops to the free tier.\n\n— XetaX");
                }
            } catch (Exception e) {
                log.warn("Subscription sweep failed for #{}: {}", sub.getId(), e.getMessage());
            }
        }
    }

    private void expire(OrgSubscription sub) {
        LocalDateTime now = LocalDateTime.now();
        sub.setStatus("EXPIRED");
        sub.setUpdatedAt(now);
        subscriptionRepository.save(sub);

        // Back to the free tier: TRIAL with the trial window already over.
        OrgPlan plan = quotaService.planOf(sub.getOwnerUserId());
        plan.setPlanKey("TRIAL");
        if (plan.getTrialEndsAt() == null || plan.getTrialEndsAt().isAfter(now)) {
            plan.setTrialEndsAt(now);
        }
        plan.setUpdatedAt(now);
        planRepository.save(plan);

        log.info("Subscription #{} expired: {} dropped from {}", sub.getId(), sub.getOwnerUserId(), sub.getPlanKey());
        notifyOwner(sub.getOwnerUserId(),
                "Your " + planLabel(sub) + " plan has expired",
                "The workspace is on the free tier now — contact us to renew.",
                "XetaX: your plan has expired",
                "Hi,\n\nYour " + planLabel(sub) + " plan ended on " + sub.getEndsAt().format(DATE)
                        + " and the workspace is on the free tier now. Contact us any time to renew.\n\n— XetaX");
    }

    private void notifyOwner(String ownerUserId, String bellTitle, String bellBody,
                             String mailSubject, String mailBody) {
        try {
            notificationService.push(ownerUserId, ownerUserId, "SYSTEM", bellTitle, bellBody, "/app/ai");
        } catch (Exception e) {
            log.warn("Subscription bell failed: {}", e.getMessage());
        }
        try {
            String email = authUserRepository.findById(UUID.fromString(ownerUserId))
                    .map(u -> u.getEmail()).orElse(null);
            if (email != null) orgSmtpService.sendAs(ownerUserId, email, mailSubject, mailBody);
        } catch (Exception e) {
            log.warn("Subscription email failed for {}: {}", ownerUserId, e.getMessage());
        }
    }

    private static String planLabel(OrgSubscription sub) {
        PlanCatalog.Plan plan = PlanCatalog.plan(sub.getPlanKey());
        return plan == null ? sub.getPlanKey() : plan.label();
    }

    private static String prefixNote(String existing, String prefix) {
        if (existing == null || existing.isBlank()) return prefix;
        return prefix + " · " + existing;
    }
}
