package com.xetax.crm.billing;

import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The non-AI side of the plan: how many members, forms and records an org may
 * hold. Checked ONLY at create-points — reading existing data is never gated,
 * so a downgrade (or an expired trial) locks growth, not access.
 *
 * <p>A failed check throws a friendly 400 naming the limit and the fix; an
 * unexpected metering error fails OPEN with a log line — limits must never
 * take the product down.
 */
@Service
@Slf4j
public class PlanLimitService {

    private final AiQuotaService quotaService;
    private final FormRepo formRepo;
    private final RecordRepo recordRepo;
    private final AuthUserRepository authUserRepository;

    /**
     * When Business stopped including unlimited users.
     *
     * <p>Accounts that already existed keep what they signed up for; only
     * accounts opened on or after this date get the catalog's number. Dated
     * rather than backfilled so nothing has to be written to existing rows —
     * their own signup date is the record.
     */
    private final Instant memberLimitChangedAt;

    public PlanLimitService(AiQuotaService quotaService, FormRepo formRepo,
                            RecordRepo recordRepo, AuthUserRepository authUserRepository,
                            @Value("${xetax.billing.member-limit-changed-at:2026-09-28}")
                            String memberLimitChangedAt) {
        this.quotaService = quotaService;
        this.formRepo = formRepo;
        this.recordRepo = recordRepo;
        this.authUserRepository = authUserRepository;
        this.memberLimitChangedAt =
                LocalDate.parse(memberLimitChangedAt).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private PlanCatalog.Plan limitsOf(String ownerUserId) {
        return quotaService.effectivePlan(quotaService.planOf(ownerUserId));
    }

    public void assertCanCreateForm(String ownerUserId) {
        try {
            PlanCatalog.Plan plan = limitsOf(ownerUserId);
            long forms = formRepo.findByOwnerUserId(ownerUserId).size();
            if (forms >= plan.maxForms()) {
                throw new BadRequestException("Your " + plan.label() + " plan allows "
                        + plan.maxForms() + " forms — upgrade to add more.");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Form limit check failed open for {}: {}", ownerUserId, e.getMessage());
        }
    }

    public void assertCanCreateRecord(String ownerUserId) {
        try {
            PlanCatalog.Plan plan = limitsOf(ownerUserId);
            List<Long> formIds = formRepo.findByOwnerUserId(ownerUserId)
                    .stream().map(FormEntity::getId).toList();
            if (formIds.isEmpty()) return;
            long records = recordRepo.countByFormIdIn(formIds);
            if (records >= plan.maxRecords()) {
                throw new BadRequestException("Your " + plan.label() + " plan allows "
                        + plan.maxRecords() + " records — upgrade to add more.");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Record limit check failed open for {}: {}", ownerUserId, e.getMessage());
        }
    }

    public void assertCanAddMember(String ownerUserId) {
        try {
            PlanCatalog.Plan plan = limitsOf(ownerUserId);
            int allowed = memberLimitOf(plan, ownerUserId);
            // members = auth users whose parent is this owner, +1 for the owner.
            long members = 1 + authUserRepository.countByParentId(ownerUserId);
            if (members >= allowed) {
                throw new BadRequestException("Your " + plan.label() + " plan allows "
                        + allowed + " team member(s) — upgrade to invite more.");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Member limit check failed open for {}: {}", ownerUserId, e.getMessage());
        }
    }

    /**
     * How many members this org may hold.
     *
     * <p>Only Business moved: it included unlimited users until
     * {@link #memberLimitChangedAt} and includes {@link PlanCatalog} many
     * after it. An account that existed before that day keeps the older,
     * larger number — it is the one they were sold. Every other plan's limit
     * is unchanged, so no other plan is grandfathered.
     */
    private int memberLimitOf(PlanCatalog.Plan plan, String ownerUserId) {
        if (!"BUSINESS".equals(plan.key()) || !existedBeforeTheChange(ownerUserId)) {
            return plan.maxMembers();
        }
        return PlanCatalog.LEGACY_BUSINESS_MAX_MEMBERS;
    }

    /**
     * The owner's own signup date decides it, not the billing row's: a plan
     * row is created lazily on first use, so an old account that had never
     * touched billing would otherwise look brand new.
     *
     * <p>An account we cannot read is treated as new. Erring the other way
     * would hand out the old allowance on a database hiccup.
     */
    private boolean existedBeforeTheChange(String ownerUserId) {
        try {
            return authUserRepository.findById(UUID.fromString(ownerUserId))
                    .map(owner -> owner.getCreateAt() != null
                            && owner.getCreateAt().isBefore(this.memberLimitChangedAt))
                    .orElse(false);
        }
        catch (Exception e) {
            log.warn("Could not date account {} for the member limit: {}",
                    ownerUserId, e.getMessage());
            return false;
        }
    }
}
