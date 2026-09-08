package com.xetax.crm.outreach;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.contact.EmailLog;
import com.xetax.crm.contact.EmailLogRepository;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.team.service.PermissionService;
import com.xetax.crm.whatsapp.dto.SendMessageRequest;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppConversationRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import com.xetax.crm.whatsapp.service.PhoneNumberService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Address-based communication used by BOTH the contact detail page and the
 * record detail page: email history + WhatsApp chat for a phone/email, and
 * sending on either channel (free text inside the 24h window, or an approved
 * template any time). History is "everything with this address", whichever
 * screen it was sent from.
 */
@Service
@RequiredArgsConstructor
public class OutreachService {

    private final CurrentUserProvider currentUserProvider;
    private final PermissionService permissionService;
    private final EmailLogRepository emailLogRepository;
    private final OrgSmtpService orgSmtpService;
    private final WhatsAppMessagingService messagingService;
    private final WhatsAppConfigRepository configRepository;
    private final WhatsAppConversationRepository conversationRepository;
    private final WhatsAppMessageRepository messageRepository;
    private final PhoneNumberService phoneNumberService;
    private final com.xetax.crm.activity.RecordActivityService activityService;

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    private void requireRead() {
        permissionService.requireAny("contacts.view", "records.view", "records.view.own");
    }

    private void requireSend() {
        permissionService.requireAny("contacts.manage", "records.edit");
    }

    // ---------------------------------------------------------------- email

    public List<EmailLog> emailHistory(String to) {
        requireRead();
        if (to == null || to.isBlank()) return List.of();
        return emailLogRepository
                .findTop50ByOwnerUserIdAndToEmailIgnoreCaseOrderByIdDesc(owner(), to.trim());
    }

    public void sendEmail(String to, String subject, String body) {
        requireSend();
        if (to == null || to.isBlank()) throw new BadRequestException("Email address is missing");
        if (subject == null || subject.isBlank() || body == null || body.isBlank()) {
            throw new BadRequestException("Subject and message are both required");
        }
        if (!orgSmtpService.isConfiguredFor(owner())) {
            throw new BadRequestException(
                    "Email is not configured yet — add your account under Profile → Email (SMTP).");
        }
        try {
            orgSmtpService.sendAs(owner(), to.trim(), subject, body);
        } catch (org.springframework.mail.MailException e) {
            throw new BadRequestException(
                    "Email failed to send — check your SMTP settings (Profile → Email). "
                    + "A FAILED entry was added to the history.");
        }
    }

    // ------------------------------------------------------------- whatsapp

    /** Chat with this phone (chronological). Empty when nothing yet / not connected. */
    public List<Map<String, Object>> whatsappHistory(String rawPhone) {
        requireRead();
        if (rawPhone == null || rawPhone.isBlank()) return List.of();
        String phone = phoneNumberService.normalize(rawPhone).orElse(null);
        if (phone == null) return List.of();
        WhatsAppConfig config = configRepository
                .findFirstByOwnerUserIdOrderByIdDesc(owner()).orElse(null);
        if (config == null) return List.of();
        return conversationRepository
                .findByWhatsappConfigIdAndCustomerPhone(config.getId(), phone)
                .map(conversation -> {
                    List<WhatsAppMessage> messages = messageRepository
                            .findByConversationIdAndOwnerUserIdOrderByIdDesc(
                                    conversation.getId(), owner(), PageRequest.of(0, 50))
                            .getContent();
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (WhatsAppMessage m : messages) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", m.getId());
                        row.put("direction", m.getDirection());
                        row.put("type", m.getMessageType());
                        row.put("body", m.getBody());
                        row.put("templateName", m.getTemplateName());
                        row.put("status", m.getStatus());
                        row.put("createdAt", m.getCreatedAt());
                        out.add(row);
                    }
                    Collections.reverse(out); // oldest first — chat order
                    return out;
                })
                .orElse(List.of());
    }

    /** Free text (24h window) ya approved template — template kabhi bhi jaa sakta hai. */
    public void sendWhatsApp(String phone, String message, String templateName,
                             String templateLanguage, String recordId, String buttonsJson) {
        requireSend();
        if (phone == null || phone.isBlank()) throw new BadRequestException("Phone number is missing");
        SendMessageRequest request = new SendMessageRequest();
        request.setPhone(phone);
        request.setMessage(message);
        request.setTemplateName(templateName);
        request.setTemplateLanguage(templateLanguage);
        request.setRecordId(recordId);
        request.setButtonsJson(buttonsJson);
        try {
            messagingService.send(request);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }

        // Record timeline — only for sends made from a record's page.
        if (recordId != null && !recordId.isBlank()) {
            java.util.UUID owner = currentUserProvider.currentDataOwnerIdOrNull();
            activityService.log(recordId, owner == null ? "" : owner.toString(), "WHATSAPP_SENT",
                    templateName != null && !templateName.isBlank()
                            ? "WhatsApp template '" + templateName + "' sent to " + phone
                            : "WhatsApp message sent to " + phone);
        }
    }
}
