package com.xetax.crm.automation.action;

import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.common.util.PlaceholderResolver;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.task.TaskItem;
import com.xetax.crm.task.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * CREATE_TASK automation action — the human fallback every pack relies on
 * when a channel is missing ("call this lead", "send the brochure by hand").
 */
@Component
@RequiredArgsConstructor
public class CreateTaskActionExecutor implements ActionExecutor {

    private final TaskRepository taskRepository;
    private final NotificationService notificationService;

    @Override
    public AutomationActionType getActionType() {
        return AutomationActionType.CREATE_TASK;
    }

    @Override
    public boolean mutatesRecord() {
        return false;
    }

    @Override
    public void execute(AutomationAction action, RecordDocument record) {
        FormEntity form = action.getAutomation().getForm();
        String owner = form.getOwnerUserId();
        Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
        String title = PlaceholderResolver.resolve(
                action.getValue() == null || action.getValue().isBlank() ? "Follow up" : action.getValue(), data).trim();
        String notes = PlaceholderResolver.resolve(action.getEmailMessage() == null ? "" : action.getEmailMessage(), data);
        int hours = 24;
        try {
            if (action.getEmailSubject() != null && !action.getEmailSubject().isBlank()) {
                hours = Math.max(1, Math.min(24 * 30, Integer.parseInt(action.getEmailSubject().trim())));
            }
        } catch (NumberFormatException ignored) { }
        String assignee = record.getAssignedTo() == null || record.getAssignedTo().isBlank() ? owner : record.getAssignedTo();

        taskRepository.save(TaskItem.builder()
                .ownerUserId(owner).createdBy(owner).assignedTo(assignee)
                .title(title.length() > 200 ? title.substring(0, 200) : title)
                .notes(notes.length() > 1000 ? notes.substring(0, 1000) : notes)
                .dueAt(LocalDateTime.now().plusHours(hours)).status("OPEN")
                .recordId(record.getId()).linkedName(form.getName())
                .remindEmail(false).remindWhatsApp(false).reminderSent(false)
                .createdAt(LocalDateTime.now())
                .build());
        notificationService.push(owner, assignee, "TASK_DUE", "New task: " + title,
                "Created by automation '" + action.getAutomation().getName() + "'",
                "/app/tasks");
    }
}
