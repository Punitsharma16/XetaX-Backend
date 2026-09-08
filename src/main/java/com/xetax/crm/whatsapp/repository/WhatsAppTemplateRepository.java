package com.xetax.crm.whatsapp.repository;

import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppTemplateRepository extends JpaRepository<WhatsAppTemplate, Long> {

    List<WhatsAppTemplate> findByOwnerUserIdAndWhatsappConfigIdOrderByNameAsc(String ownerUserId, Long configId);

    Optional<WhatsAppTemplate> findByWhatsappConfigIdAndNameAndLanguage(Long configId, String name, String language);

    void deleteByWhatsappConfigId(Long configId);
}
