package com.xetax.crm.emailcampaign.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Wires the email-campaign module: typed pacing properties + its Kafka topic.
 *
 * <p>{@code xetax.email.campaign.send} carries one message per recipient,
 * keyed by campaignId so one campaign's sends stay ordered on a partition
 * while different campaigns run in parallel — the same layout as the
 * WhatsApp campaign topic. The String/String KafkaTemplate is shared with
 * the WhatsApp module (declared in WhatsAppModuleConfig).
 */
@Configuration
@EnableConfigurationProperties(EmailCampaignProperties.class)
public class EmailCampaignModuleConfig {

    public static final String TOPIC_CAMPAIGN_SEND = "xetax.email.campaign.send";

    @Bean
    public NewTopic emailCampaignSendTopic() {
        return TopicBuilder.name(TOPIC_CAMPAIGN_SEND).partitions(3).replicas(1).build();
    }
}
