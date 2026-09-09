package com.xetax.crm.playbook;

import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.task.TaskItem;
import com.xetax.crm.task.TaskRepository;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateRepository;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Channel-agnostic outbound: the playbook decides WHAT to say, this decides
 * HOW it reaches the customer. Order: WhatsApp text (24h window open) →
 * approved WhatsApp template → email (org/global SMTP) → a task for a human
 * with the ready text. Nothing is ever silently dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageDeliveryService {

    public static final String WHATSAPP = "WHATSAPP";
    public static final String WHATSAPP_TEMPLATE = "WA_TEMPLATE";
    public static final String EMAIL = "EMAIL";
    public static final String TASK = "TASK";
    public static final String NONE = "NONE";

    private final WhatsAppMessagingService messaging;
    private final WhatsAppTemplateRepository templateRepository;
    private final OrgSmtpService smtp;
    private final TaskRepository taskRepository;

    public record Outcome(String channel, String detail) {
        public boolean delivered() { return !NONE.equals(channel); }
    }

    public record Target(String phone, String email, String name) {}

    /** Phone / email / name of a record using the same heuristics the bot uses. */
    public static Target targetOf(RecordDocument record, List<FormField> fields) {
        Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
        String phone = null, email = null, name = null;
        for (FormField f : fields) {
            String key = f.getFieldKey().toLowerCase();
            Object v = data.get(f.getFieldKey());
            if (v == null || String.valueOf(v).isBlank()) continue;
            if (phone == null && (f.getFieldType() == FieldType.PHONE || key.contains("phone") || key.contains("mobile") || key.contains("whatsapp"))) phone = String.valueOf(v);
            else if (email == null && (f.getFieldType() == FieldType.EMAIL || key.contains("email"))) email = String.valueOf(v);
            else if (name == null && f.getFieldType() == FieldType.TEXT && key.contains("name")) name = String.valueOf(v);
        }
        return new Target(phone, email, name);
    }

    /** Text message with the fallback chain. */
    public Outcome deliverText(String owner, RecordDocument record, List<FormField> fields, String text,
                               String subject, String templateName, List<String> templateParams,
                               String taskAssignee, String taskTitle) {
        Target t = targetOf(record, fields);
        if (text == null || text.isBlank()) return new Outcome(NONE, "Nothing to send");

        if (t.phone() != null) {
            try {
                if (messaging.canTextAsOwner(owner, t.phone())) {
                    messaging.sendTextAsOwner(owner, t.phone(), text);
                    return new Outcome(WHATSAPP, "WhatsApp to " + t.phone());
                }
            } catch (Exception e) {
                log.debug("WhatsApp text skipped for {}: {}", t.phone(), e.getMessage());
            }
            if (templateName != null && !templateName.isBlank()) {
                WhatsAppTemplate tpl = templateRepository
                        .findFirstByOwnerUserIdAndNameAndStatus(owner, templateName.trim(), "APPROVED").orElse(null);
                if (tpl != null) {
                    try {
                        if (messaging.sendTemplateAsOwner(owner, t.phone(), tpl.getName(), tpl.getLanguage(), templateParams, record.getId())) {
                            return new Outcome(WHATSAPP_TEMPLATE, "WhatsApp template '" + tpl.getName() + "' to " + t.phone());
                        }
                    } catch (Exception e) {
                        log.debug("WhatsApp template skipped for {}: {}", t.phone(), e.getMessage());
                    }
                }
            }
        }
        if (t.email() != null && smtp.isConfiguredFor(owner)) {
            try {
                smtp.sendAs(owner, t.email(), subject == null || subject.isBlank() ? "A quick update" : subject, text);
                return new Outcome(EMAIL, "Email to " + t.email());
            } catch (Exception e) {
                log.debug("Email skipped for {}: {}", t.email(), e.getMessage());
            }
        }
        return humanTask(owner, record, t, taskAssignee,
                taskTitle == null || taskTitle.isBlank() ? "Send this message" : taskTitle, text);
    }

    /** Document (quotation / brochure) with the fallback chain. */
    public Outcome deliverDocument(String owner, RecordDocument record, List<FormField> fields, byte[] bytes,
                                   String filename, String contentType, String caption, String subject,
                                   String taskAssignee) {
        Target t = targetOf(record, fields);
        if (t.phone() != null) {
            try {
                if (messaging.canTextAsOwner(owner, t.phone())) {
                    messaging.sendDocumentAsOwner(owner, t.phone(), bytes, filename, contentType, caption);
                    return new Outcome(WHATSAPP, "Document '" + filename + "' on WhatsApp to " + t.phone());
                }
            } catch (Exception e) {
                log.debug("WhatsApp document skipped for {}: {}", t.phone(), e.getMessage());
            }
        }
        if (t.email() != null) {
            try {
                smtp.sendWithAttachment(owner, t.email(),
                        subject == null || subject.isBlank() ? "Document: " + filename : subject,
                        caption == null ? "" : caption, filename, bytes, contentType);
                return new Outcome(EMAIL, "Document '" + filename + "' emailed to " + t.email());
            } catch (Exception e) {
                log.debug("Email attachment skipped for {}: {}", t.email(), e.getMessage());
            }
        }
        return humanTask(owner, record, t, taskAssignee, "Send document: " + filename,
                caption == null ? "" : caption);
    }

    /** Last resort — a person gets a task with the ready-to-send text. */
    public Outcome humanTask(String owner, RecordDocument record, Target t, String assignee, String title, String body) {
        String who = t.name() != null ? t.name() : t.phone() != null ? t.phone() : t.email() != null ? t.email() : "customer";
        String assignedTo = assignee == null || assignee.isBlank() ? owner : assignee;
        try {
            taskRepository.save(TaskItem.builder()
                    .ownerUserId(owner).createdBy(owner).assignedTo(assignedTo)
                    .title(trim(title + " — " + who, 200))
                    .notes(trim(body, 1000))
                    .dueAt(LocalDateTime.now().plusHours(1))
                    .status("OPEN").recordId(record.getId()).linkedName(trim(who, 160))
                    .remindEmail(false).remindWhatsApp(false).reminderSent(false)
                    .createdAt(LocalDateTime.now())
                    .build());
            return new Outcome(TASK, "Task for " + (assignedTo.equals(owner) ? "owner" : "assignee") + ": " + title);
        } catch (Exception e) {
            log.warn("Playbook task creation failed: {}", e.getMessage());
            return new Outcome(NONE, "No channel available and task failed: " + e.getMessage());
        }
    }

    static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
