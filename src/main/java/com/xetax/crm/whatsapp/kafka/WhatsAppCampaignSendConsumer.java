package com.xetax.crm.whatsapp.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.config.WhatsAppModuleConfig;
import com.xetax.crm.whatsapp.service.WhatsAppCampaignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Campaign send worker. One Kafka message = one recipient. The service call
 * is idempotent (QUEUED-only) and blocks on the per-owner rate limiter, so
 * the consumer naturally paces itself to WHATSAPP_SENDS_PER_MINUTE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppCampaignSendConsumer {

    private final WhatsAppCampaignService campaignService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = WhatsAppModuleConfig.TOPIC_CAMPAIGN_SEND, groupId = "xetax-crm")
    public void onSendJob(String payloadJson) {
        try {
            long recipientId = objectMapper.readTree(payloadJson).path("recipientId").asLong(0);
            if (recipientId > 0) {
                campaignService.processRecipient(recipientId);
            }
        } catch (Exception e) {
            // swallow — a poison message must not block the partition
            log.error("Campaign send job failed: {}", e.getMessage());
        }
    }
}
