package com.xetax.crm.automation.action;

import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.common.email.EmailService;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.common.util.PlaceholderResolver;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormField;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SendEmailActionExecutor implements ActionExecutor {

    private final EmailService emailService;
    private final OrgSmtpService orgSmtpService;

    @Override
    public AutomationActionType getActionType() {
        return AutomationActionType.SEND_EMAIL;
    }

    /** Sending mail never touches the record — no save needed for this action. */
    @Override
    public boolean mutatesRecord() {
        return false;
    }

    @Override
    public void execute(AutomationAction action, RecordDocument record) {

        FormField recipientField = action.getFormField();
        if (recipientField == null) {
            throw new IllegalArgumentException(
                    "Recipient email field is required for SEND_EMAIL action."
            );
        }

        Object raw = record.getData().get(recipientField.getFieldKey());
        String to = raw == null ? "" : raw.toString().trim();
        if (to.isEmpty()) {
            throw new IllegalArgumentException(
                    "Record has no email value in field '" + recipientField.getFieldKey() + "'."
            );
        }

        String subject = PlaceholderResolver.resolve(
                orDefault(action.getEmailSubject(), "Notification"), record.getData());
        String body = PlaceholderResolver.resolve(
                orDefault(action.getEmailMessage(), ""), record.getData());

        // Form owner ki panel-configured SMTP se jaata hai (global .env fallback).
        orgSmtpService.sendAs(action.getAutomation().getForm().getOwnerUserId(),
                to, subject, body);
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
