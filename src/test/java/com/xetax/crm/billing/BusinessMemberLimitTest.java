package com.xetax.crm.billing;

import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Business included unlimited users until the site started saying 8.
 *
 * <p>The people who signed up before that bought the older promise, so the
 * change applies to new accounts only — and it has to hold without touching a
 * single existing row, which is why each account's own signup date decides
 * rather than a backfilled column.
 */
class BusinessMemberLimitTest {

    private static final UUID OWNER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String CHANGED_AT = "2026-09-28";

    private AiQuotaService quotaService;
    private AuthUserRepository users;
    private PlanLimitService limits;

    @BeforeEach
    void setUp() {
        quotaService = mock(AiQuotaService.class);
        users = mock(AuthUserRepository.class);
        limits = new PlanLimitService(quotaService, mock(FormRepo.class), mock(RecordRepo.class),
                users, CHANGED_AT);
    }

    /** Put the org on a plan. */
    private void onPlan(String planKey) {
        OrgPlan plan = OrgPlan.builder()
                .ownerUserId(OWNER.toString()).planKey(planKey).topupBalance(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(quotaService.planOf(OWNER.toString())).thenReturn(plan);
        when(quotaService.effectivePlan(plan)).thenReturn(PlanCatalog.plan(planKey));
    }

    /** The owner account, opened on the given day. */
    private void signedUpOn(String isoDate) {
        AuthUserEntity owner = new AuthUserEntity();
        owner.setId(OWNER);
        owner.setCreateAt(Instant.parse(isoDate + "T00:00:00Z"));
        when(users.findById(OWNER)).thenReturn(Optional.of(owner));
    }

    /** How many members the org already has, owner included. */
    private void alreadyHas(long members) {
        when(users.countByParentId(anyString())).thenReturn(members - 1);
    }

    // ------------------------------------------------------------ new accounts

    @Test
    void aNewBusinessAccountStopsAtEight() {
        onPlan("BUSINESS");
        signedUpOn("2026-10-01");
        alreadyHas(8);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> limits.assertCanAddMember(OWNER.toString()));
        assertTrue(refused.getMessage().contains("8 team member(s)"), refused.getMessage());
    }

    @Test
    void aNewBusinessAccountCanStillReachEight() {
        onPlan("BUSINESS");
        signedUpOn("2026-10-01");
        alreadyHas(7);

        assertDoesNotThrow(() -> limits.assertCanAddMember(OWNER.toString()));
    }

    @Test
    void anAccountOpenedOnTheDayItselfIsANewOne() {
        // The cutoff is the start of the day: 28 Sep is already the new deal.
        onPlan("BUSINESS");
        signedUpOn("2026-09-28");
        alreadyHas(8);

        assertThrows(BadRequestException.class,
                () -> limits.assertCanAddMember(OWNER.toString()));
    }

    // ------------------------------------------------------- existing accounts

    @Test
    void anAccountThatExistedBeforeKeepsWhatItWasSold() {
        onPlan("BUSINESS");
        signedUpOn("2026-09-01");
        alreadyHas(8);

        assertDoesNotThrow(() -> limits.assertCanAddMember(OWNER.toString()),
                "an existing Business account must not be cut down to 8");
    }

    @Test
    void anExistingAccountIsNotUnlimitedEither() {
        onPlan("BUSINESS");
        signedUpOn("2026-09-01");
        alreadyHas(PlanCatalog.LEGACY_BUSINESS_MAX_MEMBERS);

        assertThrows(BadRequestException.class,
                () -> limits.assertCanAddMember(OWNER.toString()));
    }

    // ------------------------------------------------------------ other plans

    @Test
    void growthIsUntouchedWhicheverSideOfTheDateItSignedUp() {
        // Growth always allowed 5; nothing about it changed, so nothing is
        // grandfathered.
        onPlan("GROWTH");
        signedUpOn("2026-09-01");
        alreadyHas(5);
        assertThrows(BadRequestException.class,
                () -> limits.assertCanAddMember(OWNER.toString()));

        signedUpOn("2026-10-01");
        assertThrows(BadRequestException.class,
                () -> limits.assertCanAddMember(OWNER.toString()));
    }

    @Test
    void anAccountWeCannotDateIsTreatedAsNew() {
        // Erring the other way would hand out the old allowance whenever the
        // user lookup hiccups.
        onPlan("BUSINESS");
        when(users.findById(OWNER)).thenReturn(Optional.empty());
        alreadyHas(8);

        assertThrows(BadRequestException.class,
                () -> limits.assertCanAddMember(OWNER.toString()));
    }

    @Test
    void theCatalogSaysEightForBusinessNow() {
        org.junit.jupiter.api.Assertions.assertEquals(8, PlanCatalog.plan("BUSINESS").maxMembers());
        org.junit.jupiter.api.Assertions.assertEquals(5, PlanCatalog.plan("GROWTH").maxMembers());
    }
}
