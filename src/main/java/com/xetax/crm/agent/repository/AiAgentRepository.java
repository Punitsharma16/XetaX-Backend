package com.xetax.crm.agent.repository;

import com.xetax.crm.agent.entity.AiAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiAgentRepository extends JpaRepository<AiAgent, Long> {

    List<AiAgent> findByOwnerUserIdOrderByIdDesc(String ownerUserId);

    Optional<AiAgent> findByIdAndOwnerUserId(Long id, String ownerUserId);

    Optional<AiAgent> findByPublicKey(String publicKey);
}
