package com.xetax.crm.playbook;

import com.xetax.crm.activity.RecordActivityService;
import com.xetax.crm.agent.entity.AiAgent;
import com.xetax.crm.automation.engine.AutomationEngine;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.billing.AiQuotaService;
import com.xetax.crm.common.util.PlaceholderResolver;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.repository.StageRepo;
import com.xetax.crm.document.DocumentFile;
import com.xetax.crm.document.DocumentFileRepository;
import com.xetax.crm.document.DocumentPersonalizer;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.task.TaskItem;
import com.xetax.crm.task.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes one playbook action on one record. Everything here runs without
 * a security context (scheduler thread), so it only touches repositories
 * and the owner-scoped senders — never the UI services.
 */
@Slf4j
@Service
public class PlaybookActions {

    private final MessageDeliveryService delivery;
    private final DocumentFileRepository documents;
    private final DocumentPersonalizer personalizer;
    private final StageRepo stageRepo;
    private final RecordRepo recordRepo;
    private final AutomationEngine automationEngine;
    private final RecordActivityService activity;
    private final NotificationService notifications;
    private final TaskRepository taskRepository;
    private final AiQuotaService quota;
    private final ChatClient chatClient;

    public PlaybookActions(MessageDeliveryService delivery, DocumentFileRepository documents,
                           DocumentPersonalizer personalizer, StageRepo stageRepo, RecordRepo recordRepo,
                           AutomationEngine automationEngine, RecordActivityService activity,
                           NotificationService notifications, TaskRepository taskRepository,
                           AiQuotaService quota, ChatClient.Builder builder) {
        this.delivery = delivery; this.documents = documents; this.personalizer = personalizer;
        this.stageRepo = stageRepo; this.recordRepo = recordRepo; this.automationEngine = automationEngine;
        this.activity = activity; this.notifications = notifications; this.taskRepository = taskRepository;
        this.quota = quota;
        this.chatClient = builder.build(); // no CRM tools, no memory — one-shot copywriting
    }

    /** Everything an action needs to know about where it runs. */
    public record Context(SalesPlaybook playbook, FormEntity form, List<FormField> fields,
                          List<FormStage> stages, AiAgent agent, String channelHint) {}

    public MessageDeliveryService.Outcome execute(Context ctx, PlaybookRule rule, RecordDocument record, String payload) {
        String action = rule.getAction() == null ? "SEND_MESSAGE" : rule.getAction();
        return switch (action) {
            case "SEND_MESSAGE" -> sendMessage(ctx, rule, record, payload);
            case "SEND_DOCUMENT" -> sendDocument(ctx, rule, record);
            case "MOVE_STAGE" -> moveStage(ctx, rule, record);
            case "CREATE_TASK" -> createTask(ctx, rule, record, false);
            case "HANDOFF" -> createTask(ctx, rule, record, true);
            case "NOTIFY" -> notify(ctx, rule, record);
            default -> new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, "Unknown action " + action);
        };
    }

    /* ------------------------------------------------------------ actions */

    private MessageDeliveryService.Outcome sendMessage(Context ctx, PlaybookRule rule, RecordDocument record, String payload) {
        String text = compose(ctx, rule, record, payload);
        if (text == null || text.isBlank()) {
            return new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, "No message text");
        }
        List<String> params = new ArrayList<>();
        MessageDeliveryService.Target t = MessageDeliveryService.targetOf(record, ctx.fields());
        Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
        if (rule.getTemplateParams() != null && !rule.getTemplateParams().isEmpty()) {
            for (String key : rule.getTemplateParams()) {
                Object v = data.get(key);
                params.add(v == null ? "" : String.valueOf(v));
            }
        } else if (t.name() != null) {
            params.add(t.name());
        }
        MessageDeliveryService.Outcome out = delivery.deliverText(ctx.playbook().getOwnerUserId(), record, ctx.fields(),
                text, subjectFor(ctx, rule), rule.getTemplateName(), params, record.getAssignedTo(),
                rule.getTaskTitle() == null ? "Follow up" : rule.getTaskTitle());
        logActivity(ctx, record, out, rule.getName(), text);
        return out;
    }

    private MessageDeliveryService.Outcome sendDocument(Context ctx, PlaybookRule rule, RecordDocument record) {
        Long docId = rule.getDocumentId() != null ? rule.getDocumentId() : ctx.playbook().getQuotationDocumentId();
        if (docId == null) return new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, "No document configured");
        DocumentFile doc = documents.findByIdAndOwnerUserId(docId, ctx.playbook().getOwnerUserId()).orElse(null);
        if (doc == null) return new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, "Document not found");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(doc.getStoragePath()));
            if (doc.isSupportsVariables()) bytes = personalizer.personalize(bytes, record.getData() == null ? Map.of() : record.getData());
        } catch (Exception e) {
            return new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, "Document unreadable: " + e.getMessage());
        }
        String caption = compose(ctx, rule, record, null);
        if (caption == null || caption.isBlank()) caption = "Sharing " + doc.getName() + " as discussed.";
        MessageDeliveryService.Outcome out = delivery.deliverDocument(ctx.playbook().getOwnerUserId(), record, ctx.fields(),
                bytes, doc.getOriginalFilename() == null ? doc.getName() : doc.getOriginalFilename(),
                doc.getContentType(), caption, subjectFor(ctx, rule), record.getAssignedTo());
        logActivity(ctx, record, out, rule.getName(), caption);
        return out;
    }

    private MessageDeliveryService.Outcome moveStage(Context ctx, PlaybookRule rule, RecordDocument record) {
        if (rule.getTargetStageId() == null) return new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, "No target stage");
        FormStage target = ctx.stages().stream().filter(s -> s.getId().equals(rule.getTargetStageId())).findFirst().orElse(null);
        if (target == null) return new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, "Stage not in this form");
        if (target.getId().equals(record.getStageId())) return new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, "Already there");
        FormStage current = ctx.stages().stream().filter(s -> s.getId().equals(record.getStageId())).findFirst().orElse(null);
        if (current != null && Boolean.TRUE.equals(current.getIsFinal())) {
            return new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, "Record is in a final stage");
        }
        record.setStageId(target.getId());
        record.setUpdatedAt(LocalDateTime.now());
        RecordDocument saved = recordRepo.save(record);
        try { automationEngine.execute(AutomationTrigger.STAGE_CHANGED, ctx.form(), saved); }
        catch (Exception e) { log.warn("STAGE_CHANGED automation after playbook move failed: {}", e.getMessage()); }
        activity.log(record.getId(), ctx.playbook().getOwnerUserId(), "STAGE_CHANGED",
                "Stage: " + (current == null ? "?" : current.getName()) + " → " + target.getName() + " (playbook: " + rule.getName() + ")");
        return new MessageDeliveryService.Outcome("STAGE", "Moved to " + target.getName());
    }

    private MessageDeliveryService.Outcome createTask(Context ctx, PlaybookRule rule, RecordDocument record, boolean handoff) {
        MessageDeliveryService.Target t = MessageDeliveryService.targetOf(record, ctx.fields());
        String who = t.name() != null ? t.name() : t.phone() != null ? t.phone() : "customer";
        String title = rule.getTaskTitle() == null || rule.getTaskTitle().isBlank()
                ? (handoff ? "Take over this lead" : "Follow up") : rule.getTaskTitle();
        String notes = compose(ctx, rule, record, null);
        int hours = rule.getTaskDueHours() == null || rule.getTaskDueHours() < 1 ? 4 : rule.getTaskDueHours();
        String owner = ctx.playbook().getOwnerUserId();
        String assignee = record.getAssignedTo() == null || record.getAssignedTo().isBlank() ? owner : record.getAssignedTo();
        taskRepository.save(TaskItem.builder()
                .ownerUserId(owner).createdBy(owner).assignedTo(assignee)
                .title(MessageDeliveryService.trim(title + " — " + who, 200))
                .notes(MessageDeliveryService.trim(notes == null ? "" : notes, 1000))
                .dueAt(LocalDateTime.now().plusHours(hours)).status("OPEN")
                .recordId(record.getId()).linkedName(MessageDeliveryService.trim(who, 160))
                .remindEmail(false).remindWhatsApp(false).reminderSent(false)
                .createdAt(LocalDateTime.now()).build());
        if (handoff) {
            notifications.push(owner, assignee, "PLAYBOOK", "Lead needs a person: " + who,
                    rule.getName() + (notes == null || notes.isBlank() ? "" : " — " + MessageDeliveryService.trim(notes, 200)),
                    "/app/records/" + ctx.form().getSlug() + "/" + record.getId());
        }
        activity.log(record.getId(), owner, "PLAYBOOK", (handoff ? "Handed to " : "Task for ")
                + (assignee.equals(owner) ? "owner" : "assignee") + ": " + title + " (" + rule.getName() + ")");
        return new MessageDeliveryService.Outcome(MessageDeliveryService.TASK, title);
    }

    private MessageDeliveryService.Outcome notify(Context ctx, PlaybookRule rule, RecordDocument record) {
        MessageDeliveryService.Target t = MessageDeliveryService.targetOf(record, ctx.fields());
        String who = t.name() != null ? t.name() : t.phone() != null ? t.phone() : "a lead";
        String owner = ctx.playbook().getOwnerUserId();
        String body = compose(ctx, rule, record, null);
        String target = record.getAssignedTo() == null || record.getAssignedTo().isBlank() ? owner : record.getAssignedTo();
        notifications.push(owner, target, "PLAYBOOK", rule.getName() + ": " + who,
                body == null || body.isBlank() ? ctx.form().getName() : MessageDeliveryService.trim(body, 900),
                "/app/records/" + ctx.form().getSlug() + "/" + record.getId());
        if (!target.equals(owner)) {
            notifications.push(owner, owner, "PLAYBOOK", rule.getName() + ": " + who,
                    body == null || body.isBlank() ? ctx.form().getName() : MessageDeliveryService.trim(body, 900),
                    "/app/records/" + ctx.form().getSlug() + "/" + record.getId());
        }
        activity.log(record.getId(), owner, "PLAYBOOK", "Team notified: " + rule.getName());
        return new MessageDeliveryService.Outcome("NOTIFY", rule.getName());
    }

    /* ------------------------------------------------------------ helpers */

    /** Static text with placeholders, or an AI-written message when the rule asks for it. */
    String compose(Context ctx, PlaybookRule rule, RecordDocument record, String payload) {
        Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
        String base = rule.getMessage() == null ? "" : PlaceholderResolver.resolve(rule.getMessage(), data);
        if (payload != null && !payload.isBlank()) base = base.isBlank() ? payload : base + "\n" + payload;
        if (!rule.isAiCompose() || ctx.agent() == null) return base;
        String owner = ctx.playbook().getOwnerUserId();
        if (!quota.tryConsumeAgent(owner, ctx.agent().getId(), ctx.agent().getName())) return base;
        try {
            String written = chatClient.prompt()
                    .system(composePrompt(ctx, rule, base))
                    .user(recordBlock(ctx, record))
                    .call().content();
            if (written != null && !written.isBlank()) {
                String out = written.trim();
                if (out.startsWith("\"") && out.endsWith("\"") && out.length() > 2) out = out.substring(1, out.length() - 1);
                return MessageDeliveryService.trim(out, 1500);
            }
        } catch (Exception e) {
            log.warn("Playbook AI compose failed ({}): {}", rule.getName(), e.getMessage());
        }
        return base;
    }

    private String composePrompt(Context ctx, PlaybookRule rule, String hint) {
        AiAgent agent = ctx.agent();
        return """
                You are "%s", writing ONE short proactive %s message to a lead on behalf of this business.
                %s

                SALES GOAL: %s
                THIS MESSAGE IS FOR: %s
                %s
                RULES:
                - Max 70 words, warm, human, no bullet points, no subject line, no markdown.
                - Use the lead's name if known. Refer to what is already known about them.
                - Never invent prices, discounts, dates or promises. Ask one clear question or offer one clear next step.
                - Write in the language the lead used earlier (Hindi / Hinglish / English); default to friendly English.
                - Output ONLY the message text.
                """.formatted(
                agent.getName(),
                ctx.channelHint() == null ? "WhatsApp/email" : ctx.channelHint(),
                agent.getPersona() == null ? "" : agent.getPersona(),
                ctx.playbook().getGoal() == null || ctx.playbook().getGoal().isBlank() ? "move the lead to the next step" : ctx.playbook().getGoal(),
                rule.getName() == null ? "a follow-up" : rule.getName(),
                hint == null || hint.isBlank() ? "" : "STARTING POINT (rewrite naturally, keep the intent): " + hint);
    }

    private String recordBlock(Context ctx, RecordDocument record) {
        StringBuilder sb = new StringBuilder("LEAD RECORD:\n");
        Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
        int n = 0;
        for (FormField f : ctx.fields()) {
            Object v = data.get(f.getFieldKey());
            if (v == null || String.valueOf(v).isBlank()) continue;
            sb.append("- ").append(f.getLabel()).append(": ").append(MessageDeliveryService.trim(String.valueOf(v), 160)).append('\n');
            if (++n >= 20) break;
        }
        ctx.stages().stream().filter(s -> s.getId().equals(record.getStageId())).findFirst()
                .ifPresent(s -> sb.append("- Current stage: ").append(s.getName()).append('\n'));
        Object summary = data.get("ai_summary");
        if (summary != null && !String.valueOf(summary).isBlank()) {
            sb.append("- Last conversation summary: ").append(MessageDeliveryService.trim(String.valueOf(summary), 600)).append('\n');
        }
        return sb.toString();
    }

    private String subjectFor(Context ctx, PlaybookRule rule) {
        return (rule.getName() == null || rule.getName().isBlank() ? "A quick update" : rule.getName())
                + " · " + ctx.form().getName();
    }

    private void logActivity(Context ctx, RecordDocument record, MessageDeliveryService.Outcome out, String ruleName, String text) {
        String type = MessageDeliveryService.TASK.equals(out.channel()) || MessageDeliveryService.NONE.equals(out.channel())
                ? "PLAYBOOK" : "WHATSAPP_SENT".equals(out.channel()) ? "WHATSAPP_SENT" : "PLAYBOOK";
        activity.log(record.getId(), ctx.playbook().getOwnerUserId(), type,
                ruleName + " → " + out.detail() + ": " + MessageDeliveryService.trim(text, 300));
    }
}
