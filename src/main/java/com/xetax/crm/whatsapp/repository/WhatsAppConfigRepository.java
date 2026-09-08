package com.xetax.crm.whatsapp.repository;

import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppConfigRepository extends JpaRepository<WhatsAppConfig, Long> {

    Optional<WhatsAppConfig> findFirstByOwnerUserIdOrderByIdDesc(String ownerUserId);

    Optional<WhatsAppConfig> findByIdAndOwnerUserId(Long id, String ownerUserId);

    /** Webhook routing: phone_number_id in the event picks the owning config. */
    Optional<WhatsAppConfig> findFirstByPhoneNumberId(String phoneNumberId);

    /** Template-status webhooks arrive keyed by the WABA (entry.id). */
    Optional<WhatsAppConfig> findFirstByWabaId(String wabaId);

    List<WhatsAppConfig> findByOwnerUserId(String ownerUserId);
}
