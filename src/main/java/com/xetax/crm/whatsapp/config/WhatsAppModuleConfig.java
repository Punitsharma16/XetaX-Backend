package com.xetax.crm.whatsapp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wires the WhatsApp module: typed Meta properties + the two Kafka topics.
 *
 * <p>Topic layout (see XETAX_KAFKA_GUIDE.md):
 * <ul>
 *   <li>{@code xetax.whatsapp.campaign.send} — one message per campaign
 *       recipient, keyed by campaignId so one campaign's sends stay ordered
 *       on one partition while different campaigns run in parallel.</li>
 *   <li>{@code xetax.whatsapp.webhook.events} — Meta webhook events (DLRs +
 *       inbound messages), keyed by phoneNumberId so one number's status
 *       updates apply in order.</li>
 * </ul>
 * Topics are auto-created at boot by KafkaAdmin when the broker is up;
 * when it is down the app still boots (listeners keep retrying quietly).
 */
@Configuration
@EnableConfigurationProperties(MetaWhatsAppProperties.class)
public class WhatsAppModuleConfig {

    public static final String TOPIC_CAMPAIGN_SEND = "xetax.whatsapp.campaign.send";
    public static final String TOPIC_WEBHOOK_EVENTS = "xetax.whatsapp.webhook.events";

    /**
     * Explicit String/String template: the auto-configured KafkaTemplate has
     * wildcard generics, which cannot be injected as KafkaTemplate&lt;String,
     * String&gt;. Producer settings still come from spring.kafka.* in yaml.
     */
    @Bean
    public KafkaTemplate<String, String> whatsappKafkaTemplate(KafkaProperties kafkaProperties) {
        var factory = new DefaultKafkaProducerFactory<String, String>(
                kafkaProperties.buildProducerProperties(),
                new StringSerializer(), new StringSerializer());
        return new KafkaTemplate<>(factory);
    }

    @Bean
    public NewTopic whatsappCampaignSendTopic() {
        return TopicBuilder.name(TOPIC_CAMPAIGN_SEND).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic whatsappWebhookEventsTopic() {
        return TopicBuilder.name(TOPIC_WEBHOOK_EVENTS).partitions(3).replicas(1).build();
    }
}
