package com.xetax.crm.agent.repository;

import com.xetax.crm.agent.entity.AgentSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentSourceRepository extends JpaRepository<AgentSource, Long> {

    List<AgentSource> findByAgentIdOrderByIdDesc(Long agentId);
}
