package com.xetax.crm.emailcampaign.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.emailcampaign.config.EmailCampaignModuleConfig;
import com.xetax.crm.emailcampaign.service.EmailCampaignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Email campaign send worker. One Kafka message = one recipient. The service
 * call is idempotent (QUEUED-only) and blocks on the per-owner rate limiter,
 * so the consumer paces itself to EMAIL_CAMPAIGN_SENDS_PER_MINUTE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailCampaignSendConsumer {

    private final EmailCampaignService campaignService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = EmailCampaignModuleConfig.TOPIC_CAMPAIGN_SEND, groupId = "xetax-crm")
    public void onSendJob(String payloadJson) {
        try {
            long recipientId = objectMapper.readTree(payloadJson).path("recipientId").asLong(0);
            if (recipientId > 0) {
                campaignService.processRecipient(recipientId);
            }
        } catch (Exception e) {
            // swallow — a poison message must not block the partition
            log.error("Email campaign send job failed: {}", e.getMessage());
        }
    }
}
