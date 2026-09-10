package com.xetax.crm.meta;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the lead-ads settings; app credentials stay with the WhatsApp config. */
@Configuration
@EnableConfigurationProperties(MetaAdsProperties.class)
public class MetaModuleConfig {
}
