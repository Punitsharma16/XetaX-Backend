package com.xetax.crm.whatsapp.kafka;

import com.xetax.crm.whatsapp.config.WhatsAppModuleConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Single door to Kafka for the WhatsApp module. publish() is SYNCHRONOUS-ish:
 * it waits (bounded by producer max.block/delivery timeouts, ~3-10s worst
 * case, milliseconds normally) and returns false on any failure so callers
 * can fall back to inline processing — a down broker degrades throughput,
 * never correctness, and never loses a webhook or a campaign recipient.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public boolean publishCampaignSend(Long campaignId, String payloadJson) {
        return publish(WhatsAppModuleConfig.TOPIC_CAMPAIGN_SEND, String.valueOf(campaignId), payloadJson);
    }

    public boolean publishWebhookEvent(String phoneNumberId, String payloadJson) {
        return publish(WhatsAppModuleConfig.TOPIC_WEBHOOK_EVENTS,
                phoneNumberId == null ? "unknown" : phoneNumberId, payloadJson);
    }

    private boolean publish(String topic, String key, String value) {
        try {
            kafkaTemplate.send(topic, key, value).get();
            return true;
        } catch (Exception e) {
            log.warn("Kafka publish to {} failed ({}) — falling back to inline processing",
                    topic, e.getMessage());
            return false;
        }
    }
}
