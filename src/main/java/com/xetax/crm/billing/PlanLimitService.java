package com.xetax.crm.billing;

import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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
@RequiredArgsConstructor
@Slf4j
public class PlanLimitService {

    private final AiQuotaService quotaService;
    private final FormRepo formRepo;
    private final RecordRepo recordRepo;
    private final AuthUserRepository authUserRepository;

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
            // members = auth users whose parent is this owner, +1 for the owner.
            long members = 1 + authUserRepository.countByParentId(ownerUserId);
            if (members >= plan.maxMembers()) {
                throw new BadRequestException("Your " + plan.label() + " plan allows "
                        + plan.maxMembers() + " team member(s) — upgrade to invite more.");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Member limit check failed open for {}: {}", ownerUserId, e.getMessage());
        }
    }
}
