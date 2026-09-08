package com.xetax.crm.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled tasks (currently: starting SCHEDULED WhatsApp campaigns). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
