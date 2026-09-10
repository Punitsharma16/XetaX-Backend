package com.xetax.crm.platform;

import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.billing.*;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * The XetaX team's own console: every workspace on the platform, what plan it
 * is on, what it is using, and the two levers that matter — the plan and AI
 * credits. Read-only towards customer data: it counts records, it never opens
 * them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformService {

    private final PlatformAdminGuard guard;
    private final AuthUserRepository users;
    private final OrgPlanRepository plans;
    private final OrgSubscriptionRepository subscriptions;
    private final AiUsageMonthRepository usage;
    private final SubscriptionService subscriptionService;
    private final AiQuotaService quotaService;
    private final FormRepo formRepo;
    private final RecordRepo recordRepo;

    private static int thisMonth() {
        YearMonth ym = YearMonth.now();
        return ym.getYear() * 100 + ym.getMonthValue();
    }

    /**
     * Which workspace a user belongs to — the same rule CurrentUserProvider uses,
     * deliberately: a parentId only makes someone a member when it is a real
     * UUID. Legacy accounts carry "#", "0" or junk there and own themselves.
     */
    private static String ownerIdOf(AuthUserEntity user) {
        String parent = user.getParentId();
        if (parent != null && !parent.isBlank() && !"#".equals(parent)) {
            try {
                return UUID.fromString(parent).toString();
            } catch (IllegalArgumentException notAMember) {
                // falls through to "owns itself"
            }
        }
        return user.getId().toString();
    }

    private static boolean isOwner(AuthUserEntity user) {
        return ownerIdOf(user).equals(user.getId().toString());
    }

    public Map<String, Object> overview() {
        guard.require();
        List<AuthUserEntity> all = users.findAll();
        long owners = all.stream().filter(PlatformService::isOwner).count();
        long members = all.size() - owners;
        long disabled = all.stream().filter(u -> !u.isEnable()).count();

        Map<String, Long> byPlan = new LinkedHashMap<>();
        for (String key : PlanCatalog.PLANS.keySet()) byPlan.put(key, 0L);
        for (OrgPlan plan : plans.findAll()) {
            byPlan.merge(plan.getPlanKey() == null ? "TRIAL" : plan.getPlanKey(), 1L, Long::sum);
        }

        long paying = 0, expiringSoon = 0;
        long monthlyPaise = 0;
        LocalDateTime soon = LocalDateTime.now().plusDays(14);
        for (OrgSubscription sub : subscriptions.findByStatus("ACTIVE")) {
            paying++;
            monthlyPaise += sub.getAmountPaise();
            if (sub.getEndsAt() != null && sub.getEndsAt().isBefore(soon)) expiringSoon++;
        }

        int month = thisMonth();
        long assistant = 0, agent = 0;
        for (AiUsageMonth u : usage.findAll()) {
            if (u.getYearMonth() != month) continue;
            assistant += u.getAssistantMessages();
            agent += u.getAgentMessages();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaces", owners);
        out.put("members", members);
        out.put("disabledUsers", disabled);
        out.put("payingWorkspaces", paying);
        out.put("expiringIn14Days", expiringSoon);
        out.put("bookedAmount", monthlyPaise / 100.0);
        out.put("planCounts", byPlan);
        out.put("aiThisMonth", Map.of("assistant", assistant, "agent", agent));
        return out;
    }

    public List<Map<String, Object>> workspaces(String query, String planFilter) {
        guard.require();
        String q = query == null ? "" : query.trim().toLowerCase();
        String plan = planFilter == null || planFilter.isBlank() ? null : planFilter.trim().toUpperCase();

        Map<String, OrgPlan> planByOwner = new HashMap<>();
        for (OrgPlan p : plans.findAll()) planByOwner.put(p.getOwnerUserId(), p);
        Map<String, OrgSubscription> subByOwner = new HashMap<>();
        for (OrgSubscription s : subscriptions.findByStatus("ACTIVE")) subByOwner.put(s.getOwnerUserId(), s);
        Map<String, AiUsageMonth> usageByOwner = new HashMap<>();
        int month = thisMonth();
        for (AiUsageMonth u : usage.findAll()) if (u.getYearMonth() == month) usageByOwner.put(u.getOwnerUserId(), u);

        Map<String, Integer> memberCount = new HashMap<>();
        Map<String, List<Long>> formIds = new HashMap<>();
        for (AuthUserEntity user : users.findAll()) {
            if (!isOwner(user)) memberCount.merge(ownerIdOf(user), 1, Integer::sum);
        }
        for (FormEntity form : formRepo.findAll()) {
            if (form.getOwnerUserId() != null) {
                formIds.computeIfAbsent(form.getOwnerUserId(), k -> new ArrayList<>()).add(form.getId());
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (AuthUserEntity owner : users.findAll()) {
            if (!isOwner(owner)) continue;
            String id = owner.getId().toString();
            OrgPlan orgPlan = planByOwner.get(id);
            String planKey = orgPlan == null ? "TRIAL" : orgPlan.getPlanKey();
            if (plan != null && !plan.equals(planKey)) continue;
            if (!q.isEmpty() && !matches(owner, q)) continue;
            out.add(row(owner, orgPlan, subByOwner.get(id), usageByOwner.get(id),
                    memberCount.getOrDefault(id, 0), formIds.getOrDefault(id, List.of())));
        }
        out.sort((a, b) -> String.valueOf(b.get("createdAt")).compareTo(String.valueOf(a.get("createdAt"))));
        return out;
    }

    private static boolean matches(AuthUserEntity user, String q) {
        return (user.getEmail() != null && user.getEmail().toLowerCase().contains(q))
                || (user.getName() != null && user.getName().toLowerCase().contains(q))
                || (user.getCompany() != null && user.getCompany().toLowerCase().contains(q))
                || (user.getPhone() != null && user.getPhone().contains(q));
    }

    private Map<String, Object> row(AuthUserEntity owner, OrgPlan plan, OrgSubscription sub,
                                    AiUsageMonth used, int members, List<Long> forms) {
        String planKey = plan == null ? "TRIAL" : plan.getPlanKey();
        PlanCatalog.Plan catalog = PlanCatalog.plan(planKey);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ownerUserId", owner.getId().toString());
        m.put("name", owner.getName());
        m.put("email", owner.getEmail());
        m.put("company", owner.getCompany());
        m.put("phone", owner.getPhone());
        m.put("enabled", owner.isEnable());
        m.put("platformAdmin", Boolean.TRUE.equals(owner.getPlatformAdmin()));
        m.put("createdAt", owner.getCreateAt());
        m.put("planKey", planKey);
        m.put("planLabel", catalog.label());
        m.put("trialEndsAt", plan == null ? null : plan.getTrialEndsAt());
        m.put("topupBalance", plan == null ? 0 : plan.getTopupBalance());
        m.put("subscriptionEndsAt", sub == null ? null : sub.getEndsAt());
        m.put("subscriptionAmount", sub == null ? null : sub.getAmountPaise() / 100.0);
        m.put("members", members);
        m.put("forms", forms.size());
        m.put("records", forms.isEmpty() ? 0 : recordRepo.countByFormIdIn(forms));
        m.put("assistantUsed", used == null ? 0 : used.getAssistantMessages());
        m.put("agentUsed", used == null ? 0 : used.getAgentMessages());
        m.put("assistantQuota", catalog.assistantMonthly());
        m.put("agentQuota", catalog.agentMonthly());
        return m;
    }

    public Map<String, Object> workspace(String ownerUserId) {
        guard.require();
        AuthUserEntity owner = users.findById(UUID.fromString(ownerUserId))
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));
        List<Long> forms = new ArrayList<>();
        for (FormEntity f : formRepo.findByOwnerUserId(ownerUserId)) forms.add(f.getId());

        Map<String, Object> out = new LinkedHashMap<>(row(owner,
                plans.findByOwnerUserId(ownerUserId).orElse(null),
                subscriptions.findFirstByOwnerUserIdAndStatusOrderByIdDesc(ownerUserId, "ACTIVE").orElse(null),
                usage.findByOwnerUserIdAndYearMonth(ownerUserId, thisMonth()).orElse(null),
                users.findByParentId(ownerUserId).size(), forms));

        List<Map<String, Object>> team = new ArrayList<>();
        for (AuthUserEntity member : users.findByParentId(ownerUserId)) {
            team.add(Map.of("id", member.getId().toString(),
                    "name", member.getName() == null ? "" : member.getName(),
                    "email", member.getEmail() == null ? "" : member.getEmail(),
                    "enabled", member.isEnable()));
        }
        out.put("team", team);

        List<Map<String, Object>> history = new ArrayList<>();
        for (OrgSubscription s : subscriptions.findTop20ByOwnerUserIdOrderByIdDesc(ownerUserId)) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("planKey", s.getPlanKey());
            h.put("status", s.getStatus());
            h.put("startsAt", s.getStartsAt());
            h.put("endsAt", s.getEndsAt());
            h.put("amount", s.getAmountPaise() / 100.0);
            h.put("paymentRef", s.getPaymentRef());
            h.put("note", s.getNote());
            history.add(h);
        }
        out.put("subscriptions", history);
        return out;
    }

    /* -------------------------------------------------------------- levers */

    public record PlanRequest(String planKey, Integer months, Double amountRupees,
                              String paymentRef, String note) {}

    @Transactional
    public Map<String, Object> setPlan(String ownerUserId, PlanRequest in) {
        guard.require();
        if (in == null || in.planKey() == null) throw new BadRequestException("Pick a plan");
        subscriptionService.activate(ownerUserId, in.planKey(),
                in.months() == null ? 1 : in.months(),
                Math.round((in.amountRupees() == null ? 0 : in.amountRupees()) * 100),
                in.paymentRef(), in.note());
        log.info("Platform admin set {} on {}", in.planKey(), ownerUserId);
        return workspace(ownerUserId);
    }

    @Transactional
    public Map<String, Object> addCredit(String ownerUserId, int messages) {
        guard.require();
        if (messages == 0) throw new BadRequestException("Enter how many messages to add");
        quotaService.credit(ownerUserId, messages);
        return workspace(ownerUserId);
    }

    /** Disabling a workspace signs out the owner and the whole team at next request. */
    @Transactional
    public Map<String, Object> setEnabled(String ownerUserId, boolean enabled) {
        guard.require();
        AuthUserEntity owner = users.findById(UUID.fromString(ownerUserId))
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));
        if (Boolean.TRUE.equals(owner.getPlatformAdmin()) && !enabled) {
            throw new BadRequestException("A platform admin's own workspace cannot be disabled");
        }
        owner.setEnable(enabled);
        users.save(owner);
        for (AuthUserEntity member : users.findByParentId(ownerUserId)) {
            member.setEnable(enabled);
            users.save(member);
        }
        log.info("Workspace {} {}", ownerUserId, enabled ? "enabled" : "disabled");
        return workspace(ownerUserId);
    }

    /** Promote or demote another platform admin. Never exposed to customers. */
    @Transactional
    public Map<String, Object> setPlatformAdmin(String userId, boolean value) {
        AuthUserEntity me = guard.require();
        AuthUserEntity user = users.findById(UUID.fromString(userId))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (me.getId().equals(user.getId()) && !value) {
            throw new BadRequestException("You cannot remove your own platform access");
        }
        user.setPlatformAdmin(value);
        users.save(user);
        if (value) {
            OrgPlan plan = quotaService.planOf(user.getId().toString());
            plan.setPlanKey("PLATFORM");
            plan.setUpdatedAt(LocalDateTime.now());
            plans.save(plan);
        }
        log.info("Platform admin {} for {}", value ? "granted" : "revoked", user.getEmail());
        return Map.of("userId", userId, "platformAdmin", value);
    }

    public List<Map<String, Object>> plansCatalog() {
        guard.require();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanCatalog.Plan p : PlanCatalog.PLANS.values()) {
            out.add(Map.of("key", p.key(), "label", p.label(),
                    "assistantMonthly", p.assistantMonthly(), "agentMonthly", p.agentMonthly(),
                    "maxMembers", p.maxMembers(), "maxRecords", p.maxRecords()));
        }
        return out;
    }
}
