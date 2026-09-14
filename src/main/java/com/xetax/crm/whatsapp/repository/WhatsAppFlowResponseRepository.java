package com.xetax.crm.whatsapp.repository;

import com.xetax.crm.whatsapp.entity.WhatsAppFlowResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WhatsAppFlowResponseRepository extends JpaRepository<WhatsAppFlowResponse, Long> {

    Page<WhatsAppFlowResponse> findByOwnerUserIdOrderByIdDesc(String ownerUserId, Pageable pageable);

    Page<WhatsAppFlowResponse> findByFlowIdAndOwnerUserIdOrderByIdDesc(
            Long flowId, String ownerUserId, Pageable pageable);

    Optional<WhatsAppFlowResponse> findByFlowToken(String flowToken);
}
