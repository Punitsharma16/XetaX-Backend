package com.xetax.crm.whatsapp.client;

import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.service.SecretEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetaWhatsAppSender implements WhatsAppSender {

    private final MetaWhatsAppClient client;
    private final SecretEncryptionService encryption;

    @Override
    public WhatsAppSendResult sendText(WhatsAppConfig config, String toPhone, String body) {
        String token = tokenOf(config);
        if (token == null) {
            return WhatsAppSendResult.failed("TOKEN", "WhatsApp connection is broken — please reconnect.");
        }
        return client.sendTextMessage(config.getPhoneNumberId(), token, toPhone, body);
    }

    @Override
    public WhatsAppSendResult sendInteractive(WhatsAppConfig config, String toPhone, String body,
                                              java.util.List<String> buttons) {
        String token = tokenOf(config);
        if (token == null) {
            return WhatsAppSendResult.failed("TOKEN", "WhatsApp connection is broken — please reconnect.");
        }
        return client.sendInteractiveButtons(config.getPhoneNumberId(), token, toPhone, body, buttons);
    }

    @Override
    public WhatsAppSendResult sendTemplate(WhatsAppConfig config, String toPhone,
                                           String templateName, String language, String componentsJson) {
        String token = tokenOf(config);
        if (token == null) {
            return WhatsAppSendResult.failed("TOKEN", "WhatsApp connection is broken — please reconnect.");
        }
        return client.sendTemplateMessage(config.getPhoneNumberId(), token,
                toPhone, templateName, language, componentsJson);
    }

    @Override
    public WhatsAppSendResult sendMedia(WhatsAppConfig config, String toPhone, String mediaType,
                                        String mediaId, String caption, String filename) {
        String token = tokenOf(config);
        if (token == null) {
            return WhatsAppSendResult.failed("TOKEN", "WhatsApp connection is broken — please reconnect.");
        }
        return client.sendMediaMessage(config.getPhoneNumberId(), token,
                toPhone, mediaType, mediaId, caption, filename);
    }

    /** Uploads bytes to Meta and returns the media id (throws provider exception on failure). */
    public String uploadMedia(com.xetax.crm.whatsapp.entity.WhatsAppConfig config,
                              byte[] bytes, String filename, String mimeType) {
        String token = tokenOf(config);
        if (token == null) {
            throw new WhatsAppProviderException("TOKEN",
                    "WhatsApp connection is broken — please reconnect.", "no token");
        }
        return client.uploadMedia(config.getPhoneNumberId(), token, bytes, filename, mimeType);
    }

    /** Null when the config has no usable token — callers fail fast, no network call. */
    private String tokenOf(com.xetax.crm.whatsapp.entity.WhatsAppConfig config) {
        try {
            return config.getAccessTokenEncrypted() == null
                    ? null : encryption.decrypt(config.getAccessTokenEncrypted());
        } catch (Exception e) {
            return null;
        }
    }
}
