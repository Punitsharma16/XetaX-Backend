package com.xetax.crm.whatsapp.client;

import com.xetax.crm.whatsapp.entity.WhatsAppConfig;

/**
 * Provider abstraction — the rest of the module never talks to Meta directly,
 * so a future provider (or a test mock) only has to implement this.
 */
public interface WhatsAppSender {

    WhatsAppSendResult sendText(WhatsAppConfig config, String toPhone, String body);

    WhatsAppSendResult sendInteractive(WhatsAppConfig config, String toPhone, String body,
                                       java.util.List<String> buttons);

    WhatsAppSendResult sendTemplate(WhatsAppConfig config, String toPhone,
                                    String templateName, String language, String componentsJson);

    WhatsAppSendResult sendMedia(WhatsAppConfig config, String toPhone, String mediaType,
                                 String mediaId, String caption, String filename);
}
