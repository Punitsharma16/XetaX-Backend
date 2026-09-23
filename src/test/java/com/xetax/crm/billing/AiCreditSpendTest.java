package com.xetax.crm.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.settings.service.OrgSmtpService;

/**
 * What a bought top-up is actually for.
 *
 * <p>Credits are not the first thing spent: every message is counted against
 * the month's plan quota, and only once that is used up does a message come
 * out of the purchased balance. One pool serves the panel assistant and the
 * public agents together, and everyone in the workspace draws on it — the
 * meter is keyed on the owner, not on whoever sent the message.
 *
 * <p>The money path had no tests at all before this.
 */
class AiCreditSpendTest {

    private static final String ORG = "owner-1";

    private OrgPlanRepository plans;
    private AiUsageMonthRepository usage;
    private AiQuotaService service;
    private OrgPlan plan;
    private AiUsageMonth month;

    @BeforeEach
    void setUp() {
        plans = mock(OrgPlanRepository.class);
        usage = mock(AiUsageMonthRepository.class);
        AiAgentUsageRepository agentUsage = mock(AiAgentUsageRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        OrgSmtpService smtp = mock(OrgSmtpService.class);
        AuthUserRepository users = mock(AuthUserRepository.class);

        // STARTER: 25 assistant messages a month, no agent messages at all.
        plan = OrgPlan.builder().ownerUserId(ORG).planKey("STARTER").topupBalance(0).build();
        month = AiUsageMonth.builder().ownerUserId(ORG).yearMonth(AiQuotaService.currentYearMonth())
                .assistantMessages(0).agentMessages(0).alert80Sent(true)   // silence the 80% alert
                .build();

        when(plans.findByOwnerUserId(ORG)).thenReturn(Optional.of(plan));
        when(plans.save(any(OrgPlan.class))).thenAnswer(i -> i.getArgument(0));
        when(usage.findByOwnerUserIdAndYearMonth(anyString(), anyInt())).thenReturn(Optional.of(month));
        when(usage.save(any(AiUsageMonth.class))).thenAnswer(i -> i.getArgument(0));
        when(agentUsage.findByAgentIdAndYearMonth(any(), anyInt())).thenReturn(Optional.empty());
        when(agentUsage.save(any())).thenAnswer(i -> i.getArgument(0));

        service = new AiQuotaService(plans, usage, agentUsage, notifications, smtp, users);
    }

    @Test
    @DisplayName("while the monthly quota lasts, a top-up is not touched")
    void quotaFirst() {
        plan.setTopupBalance(500);

        service.consumeAssistant(ORG);

        assertThat(month.getAssistantMessages()).isEqualTo(1);
        assertThat(plan.getTopupBalance()).isEqualTo(500);
    }

    @Test
    @DisplayName("once the quota is gone, each message costs exactly one credit")
    void spillsIntoTheBalance() {
        month.setAssistantMessages(25);   // STARTER's whole month
        plan.setTopupBalance(3);

        service.consumeAssistant(ORG);
        assertThat(plan.getTopupBalance()).isEqualTo(2);

        service.consumeAssistant(ORG);
        service.consumeAssistant(ORG);
        assertThat(plan.getTopupBalance()).isZero();
    }

    @Test
    @DisplayName("with the quota gone and no credits, the assistant says so plainly")
    void refusesWhenBothAreEmpty() {
        month.setAssistantMessages(25);
        plan.setTopupBalance(0);

        assertThatThrownBy(() -> service.consumeAssistant(ORG))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("quota for this month is used up");

        assertThat(plan.getTopupBalance()).isZero();
    }

    @Test
    @DisplayName("a public agent answers while there is balance, and stops when there is none")
    void agentsSpendTheSamePool() {
        // STARTER gives agents no monthly messages, so an agent reply is paid
        // for out of the top-up from the very first one.
        plan.setTopupBalance(2);

        assertThat(service.tryConsumeAgent(ORG, 7L, "Sales Assistant")).isTrue();
        assertThat(plan.getTopupBalance()).isEqualTo(1);

        assertThat(service.tryConsumeAgent(ORG, 7L, "Sales Assistant")).isTrue();
        assertThat(plan.getTopupBalance()).isZero();

        assertThat(service.tryConsumeAgent(ORG, 7L, "Sales Assistant")).isFalse();
        assertThat(plan.getTopupBalance()).isZero();
    }

    @Test
    @DisplayName("the assistant and the agents draw on one balance, not two")
    void onePoolForBoth() {
        month.setAssistantMessages(25);
        plan.setTopupBalance(2);

        service.consumeAssistant(ORG);               // 2 -> 1
        service.tryConsumeAgent(ORG, 7L, "Sales");   // 1 -> 0

        assertThat(plan.getTopupBalance()).isZero();
        assertThatThrownBy(() -> service.consumeAssistant(ORG)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("a top-up adds to the balance and a correction takes it back")
    void creditAndCorrection() {
        service.credit(ORG, 1000);
        assertThat(plan.getTopupBalance()).isEqualTo(1000);

        service.credit(ORG, 5000);
        assertThat(plan.getTopupBalance()).isEqualTo(6000);

        service.credit(ORG, -1000);
        assertThat(plan.getTopupBalance()).isEqualTo(5000);
    }

    @Test
    @DisplayName("credits do not expire with the month — only the quota resets")
    void balanceSurvivesTheMonthRollover() {
        service.credit(ORG, 100);
        // A new month means a fresh usage row; the plan row, and its balance,
        // are untouched by that.
        month = AiUsageMonth.builder().ownerUserId(ORG).yearMonth(AiQuotaService.currentYearMonth())
                .assistantMessages(0).agentMessages(0).alert80Sent(true).build();
        when(usage.findByOwnerUserIdAndYearMonth(anyString(), anyInt())).thenReturn(Optional.of(month));

        service.consumeAssistant(ORG);

        assertThat(plan.getTopupBalance()).isEqualTo(100);
        assertThat(month.getAssistantMessages()).isEqualTo(1);
    }
}
