package com.xetax.crm.billing;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI usage + top-ups. The summary is visible to every member (the AI page
 * shows it); buying is owner-only. Plan CHANGES are manual for now — only
 * top-up packs go through Razorpay.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final AiQuotaService quotaService;
    private final RazorpayClient razorpay;
    private final AiTopupRepository topupRepository;
    private final AiAgentUsageRepository agentUsageRepository;
    private final CurrentUserProvider currentUserProvider;
    private final PermissionService permissionService;
    private final SubscriptionService subscriptionService;

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        String own = owner();
        // Expires a finished paid period on the spot, so planOf below is accurate.
        OrgSubscription subscription = subscriptionService.currentFor(own).orElse(null);
        OrgPlan plan = quotaService.planOf(own);
        PlanCatalog.Plan limits = quotaService.effectivePlan(plan);
        AiUsageMonth usage = quotaService.usageOf(own);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planKey", plan.getPlanKey());
        out.put("planLabel", PlanCatalog.plan(plan.getPlanKey()).label());
        boolean trialExpired = "TRIAL".equals(plan.getPlanKey())
                && plan.getTrialEndsAt() != null
                && plan.getTrialEndsAt().isBefore(LocalDateTime.now());
        out.put("trialEndsAt", plan.getTrialEndsAt());
        out.put("trialExpired", trialExpired);
        if (subscription != null) {
            long daysLeft = java.time.Duration.between(LocalDateTime.now(), subscription.getEndsAt()).toDays();
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("planKey", subscription.getPlanKey());
            sub.put("startsAt", subscription.getStartsAt());
            sub.put("endsAt", subscription.getEndsAt());
            sub.put("daysLeft", Math.max(0, daysLeft));
            out.put("subscription", sub);
        } else {
            out.put("subscription", null);
        }
        out.put("assistantQuota", limits.assistantMonthly());
        out.put("assistantUsed", usage.getAssistantMessages());
        out.put("agentQuota", limits.agentMonthly());
        out.put("agentUsed", usage.getAgentMessages());
        out.put("topupBalance", plan.getTopupBalance());
        out.put("yearMonth", usage.getYearMonth());
        out.put("isOwner", permissionService.isOwner());
        out.put("razorpayConfigured", razorpay.configured());
        out.put("razorpayKeyId", razorpay.configured() ? razorpay.keyId() : null);

        List<Map<String, Object>> agents = new ArrayList<>();
        for (AiAgentUsage row : agentUsageRepository
                .findByOwnerUserIdAndYearMonthOrderByMessagesDesc(own, usage.getYearMonth())) {
            agents.add(Map.of("agentId", row.getAgentId(),
                    "agentName", row.getAgentName() == null ? ("Agent #" + row.getAgentId()) : row.getAgentName(),
                    "messages", row.getMessages()));
        }
        out.put("agents", agents);

        List<Map<String, Object>> packs = new ArrayList<>();
        for (PlanCatalog.Pack pack : PlanCatalog.PACKS) {
            packs.add(Map.of("key", pack.key(), "label", pack.label(),
                    "messages", pack.messages(), "amountPaise", pack.amountPaise()));
        }
        out.put("packs", packs);

        List<Map<String, Object>> history = new ArrayList<>();
        for (AiTopup t : topupRepository.findTop20ByOwnerUserIdOrderByIdDesc(own)) {
            if (!"PAID".equals(t.getStatus())) continue;
            history.add(Map.of("packKey", t.getPackKey(), "messages", t.getMessages(),
                    "amountPaise", t.getAmountPaise(), "paidAt", t.getPaidAt()));
        }
        out.put("topupHistory", history);
        return ResponseUtil.success("Billing summary", out);
    }

    public record OrderRequest(String packKey) {}

    @PostMapping("/topup/order")
    public ApiResponse<Map<String, Object>> createOrder(@RequestBody OrderRequest request) {
        String own = owner();
        if (!permissionService.isOwner()) {
            throw new BadRequestException("Only the workspace owner can buy top-ups");
        }
        PlanCatalog.Pack pack = PlanCatalog.pack(request.packKey());
        if (pack == null) throw new BadRequestException("Unknown pack");

        String orderId = razorpay.createOrder(pack.amountPaise(), "topup-" + own.substring(0, 8));
        topupRepository.save(AiTopup.builder()
                .ownerUserId(own).packKey(pack.key()).messages(pack.messages())
                .amountPaise(pack.amountPaise()).razorpayOrderId(orderId)
                .status("CREATED").createdAt(LocalDateTime.now())
                .build());

        return ResponseUtil.success("Order created", Map.of(
                "orderId", orderId,
                "amountPaise", pack.amountPaise(),
                "currency", "INR",
                "keyId", razorpay.keyId(),
                "packKey", pack.key()));
    }

    public record VerifyRequest(String razorpayOrderId, String razorpayPaymentId,
                                String razorpaySignature) {}

    @PostMapping("/topup/verify")
    @Transactional
    public ApiResponse<Map<String, Object>> verify(@RequestBody VerifyRequest request) {
        String own = owner();
        AiTopup topup = topupRepository.findByRazorpayOrderId(request.razorpayOrderId())
                .orElseThrow(() -> new BadRequestException("Unknown order"));
        if (!topup.getOwnerUserId().equals(own)) throw new BadRequestException("Unknown order");

        // Re-verifying an already-credited payment must be a harmless no-op.
        if ("PAID".equals(topup.getStatus())) {
            return ResponseUtil.success("Already credited", Map.of("credited", topup.getMessages()));
        }

        if (!razorpay.verifySignature(request.razorpayOrderId(),
                request.razorpayPaymentId(), request.razorpaySignature())) {
            topup.setStatus("FAILED");
            topupRepository.save(topup);
            throw new BadRequestException("Payment verification failed — nothing was credited. "
                    + "If money was deducted it will auto-refund; contact support otherwise.");
        }

        topup.setStatus("PAID");
        topup.setRazorpayPaymentId(request.razorpayPaymentId());
        topup.setPaidAt(LocalDateTime.now());
        topupRepository.save(topup);
        quotaService.credit(own, topup.getMessages());

        return ResponseUtil.success("Top-up credited", Map.of("credited", topup.getMessages()));
    }
}
