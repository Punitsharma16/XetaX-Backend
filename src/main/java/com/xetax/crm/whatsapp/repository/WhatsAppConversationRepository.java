package com.xetax.crm.whatsapp.repository;

import com.xetax.crm.whatsapp.entity.WhatsAppConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WhatsAppConversationRepository extends JpaRepository<WhatsAppConversation, Long> {

    Optional<WhatsAppConversation> findByWhatsappConfigIdAndCustomerPhone(Long configId, String phone);

    Optional<WhatsAppConversation> findByIdAndOwnerUserId(Long id, String ownerUserId);

    Optional<WhatsAppConversation> findFirstByOwnerUserIdAndRecordIdOrderByIdDesc(String ownerUserId, String recordId);

    Page<WhatsAppConversation> findByOwnerUserIdOrderByLastMessageAtDesc(String ownerUserId, Pageable pageable);

    long countByOwnerUserIdAndUnreadCountGreaterThan(String ownerUserId, int unread);
}
