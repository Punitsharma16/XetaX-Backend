package com.xetax.crm.desk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

interface AgentChannelConfigRepository extends JpaRepository<AgentChannelConfig, Long> {
    Optional<AgentChannelConfig> findByAgentId(Long agentId);
    Optional<AgentChannelConfig> findFirstByOwnerUserIdAndWhatsappEnabledTrue(String ownerUserId);
    List<AgentChannelConfig> findByOwnerUserId(String ownerUserId);
    void deleteByAgentId(Long agentId);
}

interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    Optional<ChatSession> findByExternalKey(String externalKey);
    Optional<ChatSession> findByIdAndOwnerUserId(Long id, String ownerUserId);
    List<ChatSession> findByOwnerUserIdAndStatusAndAcceptedByOrderByLastMessageAtDesc(
            String ownerUserId, String status, String acceptedBy);
    List<ChatSession> findByOwnerUserIdAndStatusOrderByLastMessageAtDesc(String ownerUserId, String status);
    List<ChatSession> findTop5ByRecordIdAndOwnerUserIdOrderByIdDesc(String recordId, String ownerUserId);
    long countByOwnerUserIdAndStatusAndAcceptedBy(String ownerUserId, String status, String acceptedBy);

    /** Idle conversations whose summary is stale — the summary job's worklist. */
    @Query("""
            select s from ChatSession s
            where s.lastMessageAt < :idleBefore
              and (s.summaryAt is null or s.summaryAt < s.lastMessageAt)
              and s.lastCustomerAt is not null
            order by s.lastMessageAt asc
            """)
    List<ChatSession> findNeedingSummary(@Param("idleBefore") LocalDateTime idleBefore,
                                         org.springframework.data.domain.Pageable page);

    List<ChatSession> findByStatusAndChannelAndUpdatedAtBefore(String status, String channel, LocalDateTime before);
}

interface ChatSessionMessageRepository extends JpaRepository<ChatSessionMessage, Long> {
    List<ChatSessionMessage> findTop40BySessionIdOrderByIdDesc(Long sessionId);
    List<ChatSessionMessage> findTop100BySessionIdAndIdGreaterThanOrderByIdAsc(Long sessionId, Long afterId);
    List<ChatSessionMessage> findTop100BySessionIdOrderByIdDesc(Long sessionId);
    java.util.Optional<ChatSessionMessage> findTopBySessionIdOrderByIdDesc(Long sessionId);
    void deleteBySessionId(Long sessionId);
}

interface HandoffRequestRepository extends JpaRepository<HandoffRequest, Long> {
    Optional<HandoffRequest> findFirstBySessionIdAndStatus(Long sessionId, String status);
    List<HandoffRequest> findByOwnerUserIdAndStatusOrderByCreatedAtAsc(String ownerUserId, String status);
    long countByOwnerUserIdAndStatus(String ownerUserId, String status);
    List<HandoffRequest> findByStatusAndEscalatedFalseAndCreatedAtBefore(String status, LocalDateTime before);

    /* Requests nobody got to in time (expired) or sent back to the AI. They stay
       visible in the desk for a day so a customer who asked for a person is
       never lost just because the panel was closed at that moment. */
    List<HandoffRequest> findTop20ByOwnerUserIdAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
            String ownerUserId, List<String> statuses, LocalDateTime after);

    long countByOwnerUserIdAndStatusInAndCreatedAtAfter(
            String ownerUserId, List<String> statuses, LocalDateTime after);

    /** First-accept-wins: only flips the row if it is still OPEN. Returns 1 or 0. */
    @Modifying
    @Query("""
            update HandoffRequest h
               set h.status = 'ACCEPTED', h.acceptedBy = :userId, h.acceptedAt = :now
             where h.id = :id and h.status = 'OPEN'
            """)
    int claim(@Param("id") Long id, @Param("userId") String userId, @Param("now") LocalDateTime now);

    /** Picking up a missed chat later — same first-wins guard, other statuses. */
    @Modifying
    @Query("""
            update HandoffRequest h
               set h.status = 'ACCEPTED', h.acceptedBy = :userId, h.acceptedAt = :now, h.resolvedAt = null
             where h.id = :id and h.status in ('EXPIRED', 'DECLINED')
            """)
    int claimMissed(@Param("id") Long id, @Param("userId") String userId, @Param("now") LocalDateTime now);
}
