package com.xetax.crm.autopilot;

import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.repository.StageRepo;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.task.TaskRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppConversationRepository;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Follow-up autopilot: a deterministic morning digest (no LLM — cheap and
 * reliable) of what needs attention TODAY: leads going cold, unanswered
 * WhatsApp chats, tasks due. Panel notification always; email + WhatsApp
 * best-effort when configured.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutopilotService {

    private static final int STALE_DAYS = 3;

    private final FormRepo formRepo;
    private final StageRepo stageRepo;
    private final RecordRepo recordRepo;
    private final WhatsAppConversationRepository conversationRepository;
    private final TaskRepository taskRepository;
    private final NotificationService notificationService;
    private final OrgSmtpService orgSmtpService;
    private final WhatsAppMessagingService whatsAppMessagingService;
    private final AuthUserRepository authUserRepository;

    /** Har subah 9:00 — every org owner gets their digest. */
    @Scheduled(cron = "0 0 9 * * *")
    public void morningDigests() {
        formRepo.findAll().stream()
                .map(FormEntity::getOwnerUserId)
                .filter(o -> o != null && !o.isBlank())
                .distinct()
                .forEach(owner -> {
                    try {
                        runFor(owner);
                    } catch (Exception e) {
                        log.warn("Digest for {} failed: {}", owner, e.getMessage());
                    }
                });
    }

    /** Builds + delivers the digest; returns the text (used by run-now too). */
    public String runFor(String owner) {
        List<String> lines = new ArrayList<>();
        LocalDateTime staleCutoff = LocalDateTime.now().minusDays(STALE_DAYS);

        long staleTotal = 0;
        for (FormEntity form : formRepo.findByOwnerUserId(owner)) {
            List<Long> openStages = stageRepo.findByFormIdOrderBySequence(form.getId()).stream()
                    .filter(s -> !Boolean.TRUE.equals(s.getIsFinal()))
                    .map(FormStage::getId).toList();
            if (openStages.isEmpty()) continue;
            long stale = recordRepo.countByFormIdAndStageIdInAndUpdatedAtBefore(
                    form.getId(), openStages, staleCutoff);
            if (stale > 0) {
                staleTotal += stale;
                lines.add("• " + form.getName() + ": " + stale + " record"
                        + (stale == 1 ? "" : "s") + " untouched for " + STALE_DAYS + "+ days");
            }
        }

        long unread = conversationRepository.countByOwnerUserIdAndUnreadCountGreaterThan(owner, 0);
        if (unread > 0) {
            lines.add("• WhatsApp: " + unread + " chat" + (unread == 1 ? "" : "s") + " waiting for a reply");
        }

        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        long tasksToday = taskRepository.countByAssignedToAndStatusAndDueAtBetween(
                owner, "OPEN", dayStart, dayStart.plusDays(1));
        if (tasksToday > 0) {
            lines.add("• " + tasksToday + " task" + (tasksToday == 1 ? "" : "s") + " due today");
        }

        String text = lines.isEmpty()
                ? "All clear — no cold leads, no unanswered chats, no tasks due. 🎉"
                : "Good morning! These need your attention today:\n" + String.join("\n", lines);

        notificationService.push(owner, owner, "DIGEST",
                lines.isEmpty() ? "Morning digest — all clear 🎉"
                        : "Morning digest — " + lines.size() + " thing" + (lines.size() == 1 ? "" : "s") + " to check",
                text, staleTotal > 0 ? "/app/records" : "/app/tasks");

        var user = authUserRepository.findById(UUID.fromString(owner)).orElse(null);
        if (user != null && user.getEmail() != null && orgSmtpService.isConfiguredFor(owner)) {
            try {
                orgSmtpService.sendAs(owner, user.getEmail(), "XetaX morning digest", text);
            } catch (Exception e) {
                log.warn("Digest email for {} failed: {}", owner, e.getMessage());
            }
        }
        if (user != null && user.getPhone() != null && !user.getPhone().isBlank()) {
            try {
                whatsAppMessagingService.sendTextAsOwner(owner, user.getPhone(), text);
            } catch (Exception e) {
                log.debug("Digest WhatsApp for {} skipped: {}", owner, e.getMessage());
            }
        }
        return text;
    }
}
