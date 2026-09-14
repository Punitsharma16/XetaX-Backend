package com.xetax.crm.whatsapp.repository;

import com.xetax.crm.whatsapp.entity.WhatsAppFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppFlowRepository extends JpaRepository<WhatsAppFlow, Long> {

    List<WhatsAppFlow> findByOwnerUserIdOrderByIdDesc(String ownerUserId);

    Optional<WhatsAppFlow> findByIdAndOwnerUserId(Long id, String ownerUserId);

    Optional<WhatsAppFlow> findByMetaFlowId(String metaFlowId);

    Optional<WhatsAppFlow> findByOwnerUserIdAndName(String ownerUserId, String name);
}
