package com.xetax.crm.whatsapp.webhook;

import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppConversation;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;

/**
 * Published after an inbound customer message is stored. Listeners (the bot
 * desk) react asynchronously, so the webhook thread never waits on an LLM.
 */
public record WhatsAppInboundEvent(WhatsAppConfig config,
                                   WhatsAppConversation conversation,
                                   WhatsAppMessage message) {}
