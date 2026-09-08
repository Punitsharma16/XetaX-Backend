package com.xetax.crm.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Turns on JPA auditing.
 *
 * <p>{@code BaseEntity} annotates {@code createdAt} / {@code updatedAt} with
 * {@code @CreatedDate} / {@code @LastModifiedDate}, but those are only honoured
 * when auditing is enabled. Without this, every entity extending BaseEntity
 * (forms, automations, integrations) was persisted with null timestamps.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
