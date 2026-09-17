package com.xetax.crm.whatsapp.repository;

import com.xetax.crm.whatsapp.entity.WhatsAppTemplateMedia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WhatsAppTemplateMediaRepository extends JpaRepository<WhatsAppTemplateMedia, Long> {

    Optional<WhatsAppTemplateMedia> findByMediaKey(String mediaKey);
}
