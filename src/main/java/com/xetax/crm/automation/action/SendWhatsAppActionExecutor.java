package com.xetax.crm.automation.action;

import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.common.util.PlaceholderResolver;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SEND_WHATSAPP automation action. Fire-and-forget like SEND_EMAIL: the
 * message is queued (and dispatched off-thread), the record is untouched,
 * and any failure is contained by the engine's per-rule try/catch.
 */
@Component
@RequiredArgsConstructor
public class SendWhatsAppActionExecutor implements ActionExecutor {

    private final WhatsAppMessagingService messagingService;

    @Override
    public AutomationActionType getActionType() {
        return AutomationActionType.SEND_WHATSAPP;
    }

    @Override
    public boolean mutatesRecord() {
        return false;
    }

    @Override
    public void execute(AutomationAction action, RecordDocument record) {

        FormField phoneField = action.getFormField();
        if (phoneField == null) {
            throw new IllegalArgumentException(
                    "A phone field is required for the SEND_WHATSAPP action.");
        }

        Object raw = record.getData().get(phoneField.getFieldKey());
        String phone = raw == null ? "" : raw.toString().trim();
        if (phone.isEmpty()) {
            throw new IllegalArgumentException(
                    "Record has no phone value in field '" + phoneField.getFieldKey() + "'.");
        }

        String template = action.getEmailMessage();
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException(
                    "A message text is required for the SEND_WHATSAPP action.");
        }
        String body = PlaceholderResolver.resolve(template, record.getData());

        // Automations run without a security context — scope by the form owner.
        String ownerUserId = action.getAutomation().getForm().getOwnerUserId();
        messagingService.sendTextAsOwner(ownerUserId, phone, body);
    }
}
