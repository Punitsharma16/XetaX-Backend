package com.xetax.crm.emailcampaign.kafka;

import com.xetax.crm.emailcampaign.config.EmailCampaignModuleConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Single door to Kafka for email campaigns. Returns false on any failure so
 * the caller can fall back to inline processing — a down broker degrades
 * throughput, never correctness, and never loses a recipient.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailCampaignEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    /** Same switch as the WhatsApp module: no broker deployed → skip the producer. */
    @Value("${xetax.kafka.publish-enabled:true}")
    private boolean publishEnabled;

    public boolean publishCampaignSend(Long campaignId, String payloadJson) {
        if (!publishEnabled) return false;
        try {
            kafkaTemplate.send(EmailCampaignModuleConfig.TOPIC_CAMPAIGN_SEND,
                    String.valueOf(campaignId), payloadJson).get();
            return true;
        } catch (Exception e) {
            log.warn("Kafka publish to {} failed ({}) — falling back to inline processing",
                    EmailCampaignModuleConfig.TOPIC_CAMPAIGN_SEND, e.getMessage());
            return false;
        }
    }
}
