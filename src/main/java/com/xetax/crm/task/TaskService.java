package com.xetax.crm.task;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepository taskRepository;
    private final CurrentUserProvider currentUserProvider;
    private final NotificationService notificationService;
    private final OrgSmtpService orgSmtpService;
    private final WhatsAppMessagingService whatsAppMessagingService;
    private final AuthUserRepository authUserRepository;

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    private String me() {
        UUID id = currentUserProvider.currentUserIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    // ------------------------------------------------------------------ crud

    public TaskItem create(TaskItem input) {
        if (input.getTitle() == null || input.getTitle().isBlank()) {
            throw new BadRequestException("Task title is required");
        }
        if ((Boolean.TRUE.equals(input.getRemindEmail()) || Boolean.TRUE.equals(input.getRemindWhatsApp())) && input.getDueAt() == null) {
            throw new BadRequestException("A due date/time is required for reminders");
        }
        input.setId(null);
        input.setOwnerUserId(owner());
        input.setCreatedBy(me());
        if (input.getAssignedTo() == null || input.getAssignedTo().isBlank()) {
            input.setAssignedTo(me());
        }
        input.setStatus("OPEN");
        // absent JSON fields => false (DB columns are NOT NULL)
        input.setRemindEmail(Boolean.TRUE.equals(input.getRemindEmail()));
        input.setRemindWhatsApp(Boolean.TRUE.equals(input.getRemindWhatsApp()));
        input.setReminderSent(false);
        input.setCreatedAt(LocalDateTime.now());
        return taskRepository.save(input);
    }

    public List<TaskItem> myTasks(String status) {
        return taskRepository.findTop100ByAssignedToAndStatusOrderByDueAtAsc(
                me(), "DONE".equalsIgnoreCase(status) ? "DONE" : "OPEN");
    }

    public List<TaskItem> forContact(Long contactId) {
        return taskRepository.findTop50ByContactIdAndOwnerUserIdOrderByStatusDescDueAtAsc(contactId, owner());
    }

    public List<TaskItem> forRecord(String recordId) {
        return taskRepository.findTop50ByRecordIdAndOwnerUserIdOrderByStatusDescDueAtAsc(recordId, owner());
    }

    public TaskItem setDone(Long id, boolean done) {
        TaskItem task = find(id);
        task.setStatus(done ? "DONE" : "OPEN");
        task.setDoneAt(done ? LocalDateTime.now() : null);
        return taskRepository.save(task);
    }

    public void delete(Long id) {
        taskRepository.delete(find(id));
    }

    private TaskItem find(Long id) {
        return taskRepository.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
    }

    // ------------------------------------------------------------- reminders

    /** Every minute: fire due reminders — panel always, email/WhatsApp if chosen. */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void fireDueReminders() {
        List<TaskItem> due = taskRepository
                .findTop100ByStatusAndReminderSentFalseAndDueAtBefore("OPEN", LocalDateTime.now());
        for (TaskItem task : due) {
            try {
                remind(task);
            } catch (Exception e) {
                log.warn("Task reminder {} failed: {}", task.getId(), e.getMessage());
            }
            task.setReminderSent(true);
            taskRepository.save(task);
        }
    }

    private void remind(TaskItem task) {
        String context = task.getLinkedName() == null ? "" : " · " + task.getLinkedName();
        String link = task.getContactId() != null ? "/app/contacts/" + task.getContactId()
                : (task.getRecordId() != null ? "/app/tasks" : "/app/tasks");
        notificationService.push(task.getOwnerUserId(), task.getAssignedTo(), "TASK_DUE",
                "Task due: " + task.getTitle(), (task.getNotes() == null ? "" : task.getNotes()) + context, link);

        var user = authUserRepository.findById(UUID.fromString(task.getAssignedTo())).orElse(null);
        if (user == null) return;
        String text = "⏰ Task due: " + task.getTitle()
                + (task.getLinkedName() == null ? "" : " (" + task.getLinkedName() + ")")
                + (task.getNotes() == null || task.getNotes().isBlank() ? "" : "\n" + task.getNotes());
        if (Boolean.TRUE.equals(task.getRemindEmail()) && user.getEmail() != null) {
            try {
                orgSmtpService.sendAs(task.getOwnerUserId(), user.getEmail(),
                        "Task due: " + task.getTitle(), text);
            } catch (Exception e) {
                log.warn("Task {} email reminder failed: {}", task.getId(), e.getMessage());
            }
        }
        if (Boolean.TRUE.equals(task.getRemindWhatsApp()) && user.getPhone() != null && !user.getPhone().isBlank()) {
            try {
                whatsAppMessagingService.sendTextAsOwner(task.getOwnerUserId(), user.getPhone(), text);
            } catch (Exception e) {
                log.warn("Task {} WhatsApp reminder failed: {}", task.getId(), e.getMessage());
            }
        }
    }
}
