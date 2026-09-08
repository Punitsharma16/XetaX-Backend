package com.xetax.crm.billing;

import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.settings.service.OrgSmtpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The AI meter. Every assistant/agent message passes through here: it is
 * counted against the month's plan quota, spills into the purchased top-up
 * balance when the quota is gone, and is refused when both are empty.
 *
 * <p>The 80% warning email/bell goes out once per month. A metering failure
 * fails OPEN (the message goes through, a warning is logged) — billing must
 * never take the product down.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiQuotaService {

    private final OrgPlanRepository planRepository;
    private final AiUsageMonthRepository usageRepository;
    private final AiAgentUsageRepository agentUsageRepository;
    private final NotificationService notificationService;
    private final OrgSmtpService orgSmtpService;
    private final com.xetax.crm.auth.user.AuthUserRepository authUserRepository;

    static int currentYearMonth() {
        LocalDate now = LocalDate.now();
        return now.getYear() * 100 + now.getMonthValue();
    }

    /** Get-or-create the org's plan row; new orgs start on a 14-day TRIAL. */
    @Transactional
    public OrgPlan planOf(String ownerUserId) {
        return planRepository.findByOwnerUserId(ownerUserId).orElseGet(() -> {
            PlanCatalog.Plan trial = PlanCatalog.plan("TRIAL");
            return planRepository.save(OrgPlan.builder()
                    .ownerUserId(ownerUserId)
                    .planKey("TRIAL")
                    .trialEndsAt(LocalDateTime.now().plusDays(trial.trialDays()))
                    .topupBalance(0)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        });
    }

    /** TRIAL past its end date quietly behaves as STARTER. */
    public PlanCatalog.Plan effectivePlan(OrgPlan plan) {
        if ("TRIAL".equals(plan.getPlanKey())
                && plan.getTrialEndsAt() != null
                && plan.getTrialEndsAt().isBefore(LocalDateTime.now())) {
            return PlanCatalog.plan("STARTER");
        }
        return PlanCatalog.plan(plan.getPlanKey());
    }

    @Transactional
    public AiUsageMonth usageOf(String ownerUserId) {
        int ym = currentYearMonth();
        return usageRepository.findByOwnerUserIdAndYearMonth(ownerUserId, ym).orElseGet(() ->
                usageRepository.save(AiUsageMonth.builder()
                        .ownerUserId(ownerUserId).yearMonth(ym)
                        .assistantMessages(0).agentMessages(0).alert80Sent(false)
                        .build()));
    }

    /** Panel assistant message — throws a friendly 400 when nothing is left. */
    @Transactional
    public void consumeAssistant(String ownerUserId) {
        try {
            consume(ownerUserId, true, null, null);
        } catch (QuotaExhaustedException e) {
            throw new BadRequestException(
                    "Your AI quota for this month is used up. Buy a top-up on the AI page, "
                    + "or it resets on the 1st.");
        }
    }

    /** Public agent message — false means "quota gone", the widget answers politely. */
    @Transactional
    public boolean tryConsumeAgent(String ownerUserId, Long agentId, String agentName) {
        try {
            consume(ownerUserId, false, agentId, agentName);
            return true;
        } catch (QuotaExhaustedException e) {
            return false;
        } catch (Exception unexpected) {
            log.warn("AI metering failed open for {}: {}", ownerUserId, unexpected.getMessage());
            return true;
        }
    }

    private static final class QuotaExhaustedException extends RuntimeException {}

    private void consume(String ownerUserId, boolean assistant, Long agentId, String agentName) {
        OrgPlan plan = planOf(ownerUserId);
        PlanCatalog.Plan limits = effectivePlan(plan);
        AiUsageMonth usage = usageOf(ownerUserId);

        int quota = assistant ? limits.assistantMonthly() : limits.agentMonthly();
        int used = assistant ? usage.getAssistantMessages() : usage.getAgentMessages();

        if (used >= quota) {
            // Quota gone — spend from the purchased balance, if any.
            if (plan.getTopupBalance() <= 0) throw new QuotaExhaustedException();
            plan.setTopupBalance(plan.getTopupBalance() - 1);
            plan.setUpdatedAt(LocalDateTime.now());
            planRepository.save(plan);
        }

        if (assistant) usage.setAssistantMessages(used + 1);
        else usage.setAgentMessages(used + 1);
        usageRepository.save(usage);

        if (!assistant && agentId != null) {
            AiAgentUsage row = agentUsageRepository
                    .findByAgentIdAndYearMonth(agentId, usage.getYearMonth())
                    .orElseGet(() -> AiAgentUsage.builder()
                            .ownerUserId(ownerUserId).agentId(agentId).agentName(agentName)
                            .yearMonth(usage.getYearMonth()).messages(0).build());
            row.setMessages(row.getMessages() + 1);
            if (agentName != null) row.setAgentName(agentName);
            agentUsageRepository.save(row);
        }

        maybeSend80Alert(ownerUserId, plan, limits, usage);
    }

    /** One warning per month, when either pool crosses 80% of its quota. */
    private void maybeSend80Alert(String ownerUserId, OrgPlan plan,
                                  PlanCatalog.Plan limits, AiUsageMonth usage) {
        if (usage.isAlert80Sent()) return;
        boolean assistantHot = limits.assistantMonthly() > 0
                && usage.getAssistantMessages() * 100 >= limits.assistantMonthly() * 80;
        boolean agentHot = limits.agentMonthly() > 0
                && usage.getAgentMessages() * 100 >= limits.agentMonthly() * 80;
        if (!assistantHot && !agentHot) return;

        usage.setAlert80Sent(true);
        usageRepository.save(usage);

        String subject = "XetaX: 80% of your monthly AI quota is used";
        String body = "Hi,\n\nYour workspace has used "
                + usage.getAssistantMessages() + "/" + limits.assistantMonthly() + " assistant messages and "
                + usage.getAgentMessages() + "/" + limits.agentMonthly() + " website-agent messages this month"
                + " (plan: " + limits.label() + ").\n\n"
                + "When the quota runs out the AI features pause until the 1st — you can buy a top-up "
                + "any time from the AI page in your panel.\n\n— XetaX";
        try {
            notificationService.push(ownerUserId, ownerUserId, "SYSTEM",
                    "80% of your AI quota is used",
                    "Top up from the AI page to avoid an interruption.", "/app/ai");
        } catch (Exception e) {
            log.warn("Quota bell failed: {}", e.getMessage());
        }
        try {
            String ownerEmail = authUserRepository.findById(java.util.UUID.fromString(ownerUserId))
                    .map(u -> u.getEmail()).orElse(null);
            if (ownerEmail != null) orgSmtpService.sendAs(ownerUserId, ownerEmail, subject, body);
        } catch (Exception e) {
            log.warn("Quota email failed for {}: {}", ownerUserId, e.getMessage());
        }
    }

    /** Called by the verified top-up payment. */
    @Transactional
    public void credit(String ownerUserId, int messages) {
        OrgPlan plan = planOf(ownerUserId);
        plan.setTopupBalance(plan.getTopupBalance() + messages);
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);
    }
}
