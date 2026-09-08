package com.xetax.crm.whatsapp.kafka;

import com.xetax.crm.whatsapp.config.WhatsAppModuleConfig;
import com.xetax.crm.whatsapp.webhook.WhatsAppWebhookProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * DLR / inbound-message consumer. Events for one phone number share a
 * partition key, so one number's status updates apply in order. The
 * processor is idempotent, so at-least-once delivery is safe.
 */
@Component
@RequiredArgsConstructor
public class WhatsAppWebhookEventConsumer {

    private final WhatsAppWebhookProcessor processor;

    @KafkaListener(topics = WhatsAppModuleConfig.TOPIC_WEBHOOK_EVENTS, groupId = "xetax-crm")
    public void onEvent(String valueJson) {
        processor.processRawValue(valueJson);
    }
}
