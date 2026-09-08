package com.xetax.crm.billing;

import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Back-office plan management — plans are sold manually, so the XetaX team
 * activates them here with curl instead of raw SQL. Two locks: a normal
 * signed-in JWT (the path is NOT public) plus a shared secret
 * ({@code INTERNAL_ADMIN_KEY} in .env) sent as the X-Internal-Key header;
 * with the key unset every call is refused, so nothing is exposed by default.
 *
 * <pre>
 * curl -X POST https://.../api/internal/billing/subscriptions \
 *   -H "Authorization: Bearer $YOUR_LOGIN_TOKEN" \
 *   -H "X-Internal-Key: $INTERNAL_ADMIN_KEY" -H "Content-Type: application/json" \
 *   -d '{"ownerEmail":"a@b.com","planKey":"GROWTH","months":12,"amountRupees":9990,"paymentRef":"UTR123"}'
 * </pre>
 */
@RestController
@RequestMapping("/api/internal/billing")
@RequiredArgsConstructor
public class InternalBillingController {

    private final SubscriptionService subscriptionService;
    private final AuthUserRepository authUserRepository;
    private final AiQuotaService quotaService;

    @Value("${app.internal-admin-key:}")
    private String adminKey;

    private void guard(String key) {
        if (adminKey == null || adminKey.isBlank()) {
            throw new BadRequestException("Internal billing API is disabled — set INTERNAL_ADMIN_KEY");
        }
        byte[] expected = adminKey.getBytes(StandardCharsets.UTF_8);
        byte[] given = (key == null ? "" : key).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) {
            throw new BadRequestException("Bad internal key");
        }
    }

    /** The workspace owner for any known email — members resolve to their owner. */
    private String ownerIdFor(String email) {
        AuthUserEntity user = authUserRepository.findByEmail(email == null ? "" : email.trim())
                .orElseThrow(() -> new BadRequestException("No user with that email"));
        try {
            return UUID.fromString(user.getParentId()).toString();
        } catch (Exception notAMember) {
            return user.getId().toString();
        }
    }

    public record ActivateRequest(String ownerEmail, String planKey, Integer months,
                                  Double amountRupees, String paymentRef, String note) {}

    @PostMapping("/subscriptions")
    public ApiResponse<Map<String, Object>> activate(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                                     @RequestBody ActivateRequest request) {
        guard(key);
        String ownerId = ownerIdFor(request.ownerEmail());
        long paise = Math.round((request.amountRupees() == null ? 0 : request.amountRupees()) * 100);
        OrgSubscription sub = subscriptionService.activate(ownerId, request.planKey(),
                request.months() == null ? 1 : request.months(), paise,
                request.paymentRef(), request.note());
        return ResponseUtil.success("Subscription activated", row(sub));
    }

    @GetMapping("/subscriptions")
    public ApiResponse<Map<String, Object>> view(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                                 @RequestParam String ownerEmail) {
        guard(key);
        String ownerId = ownerIdFor(ownerEmail);
        OrgPlan plan = quotaService.planOf(ownerId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ownerUserId", ownerId);
        out.put("planKey", plan.getPlanKey());
        out.put("topupBalance", plan.getTopupBalance());
        out.put("current", subscriptionService.currentFor(ownerId).map(this::row).orElse(null));
        List<Map<String, Object>> history = new ArrayList<>();
        for (OrgSubscription sub : subscriptionService.history(ownerId)) history.add(row(sub));
        out.put("history", history);
        return ResponseUtil.success("Subscriptions", out);
    }

    private Map<String, Object> row(OrgSubscription sub) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", sub.getId());
        out.put("planKey", sub.getPlanKey());
        out.put("status", sub.getStatus());
        out.put("startsAt", sub.getStartsAt());
        out.put("endsAt", sub.getEndsAt());
        out.put("amountPaise", sub.getAmountPaise());
        out.put("paymentRef", sub.getPaymentRef());
        out.put("note", sub.getNote());
        return out;
    }
}
