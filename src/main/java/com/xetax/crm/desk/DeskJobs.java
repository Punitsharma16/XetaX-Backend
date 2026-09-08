package com.xetax.crm.desk;

import com.xetax.crm.agent.repository.AiAgentRepository;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.task.TaskItem;
import com.xetax.crm.task.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Background sweeps: idle-chat summaries, "nobody picked up" escalation,
 * and the website wait timeout. Each is bounded per run so a busy platform
 * never stalls on one tick.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeskJobs {

    private final ChatSessionRepository sessions;
    private final HandoffRequestRepository handoffs;
    private final BotConversationService bot;
    private final AiAgentRepository agentRepository;
    private final ChannelConfigService configService;
    private final NotificationService notificationService;
    private final TaskRepository taskRepository;

    /** Conversations quiet for 15 min get (re)summarised — max 40 per run. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void summarizeIdleChats() {
        for (ChatSession s : sessions.findNeedingSummary(LocalDateTime.now().minusMinutes(15), PageRequest.of(0, 40))) {
            try {
                bot.summarize(s);
            } catch (Exception e) {
                log.warn("Summary job failed for session {}: {}", s.getId(), e.getMessage());
            }
        }
    }

    /** OPEN for 2+ minutes with nobody accepting → nudge the owner once. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void escalateUnanswered() {
        for (HandoffRequest r : handoffs.findByStatusAndEscalatedFalseAndCreatedAtBefore("OPEN", LocalDateTime.now().minusMinutes(2))) {
            try {
                r.setEscalated(true);
                handoffs.save(r);
                notificationService.push(r.getOwnerUserId(), r.getOwnerUserId(), "HANDOFF",
                        "Nobody has picked up " + r.getCustomerLabel() + " yet",
                        "Open the chat desk to reply or hand it back to the assistant.", "/app/dashboard?desk=open");
            } catch (Exception e) {
                log.warn("Escalation failed for request {}: {}", r.getId(), e.getMessage());
            }
        }
    }

    /**
     * Website visitors can't wait forever. Past the agent's wait window the AI
     * takes the chat back, asks for contact details, and the owner gets a
     * call-back task if we already know how to reach the person.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void expireWebsiteWaits() {
        LocalDateTime now = LocalDateTime.now();
        for (ChatSession s : sessions.findByStatusAndChannelAndUpdatedAtBefore(ChatSession.STATUS_WAITING, "WEBSITE", now.minusMinutes(1))) {
            try {
                int wait = agentRepository.findById(s.getAgentId())
                        .map(configService::configFor).map(AgentChannelConfig::getWebsiteWaitMinutes).orElse(3);
                if (s.getUpdatedAt() == null || s.getUpdatedAt().isAfter(now.minusMinutes(wait))) continue;

                handoffs.findFirstBySessionIdAndStatus(s.getId(), "OPEN").ifPresent(r -> {
                    r.setStatus("EXPIRED"); r.setResolvedAt(now); handoffs.save(r);
                });
                s.setStatus(ChatSession.STATUS_AI);
                s.setUpdatedAt(now);
                sessions.save(s);
                bot.aiMessage(s, "Our team is busy right now. Share your phone number or email and they'll call you back shortly — meanwhile I'm happy to keep helping.");

                if (s.getCustomerPhone() != null || s.getCustomerEmail() != null) {
                    taskRepository.save(TaskItem.builder()
                            .ownerUserId(s.getOwnerUserId()).createdBy(s.getOwnerUserId()).assignedTo(s.getOwnerUserId())
                            .title("Call back " + BotConversationService.labelOf(s) + " (website chat)")
                            .notes(s.getSummary()).dueAt(now.plusHours(2)).status("OPEN")
                            .recordId(s.getRecordId()).contactId(s.getContactId())
                            .linkedName(BotConversationService.labelOf(s))
                            .remindEmail(false).remindWhatsApp(false).reminderSent(false)
                            .createdAt(now).build());
                }
            } catch (Exception e) {
                log.warn("Website wait expiry failed for session {}: {}", s.getId(), e.getMessage());
            }
        }
    }
}
