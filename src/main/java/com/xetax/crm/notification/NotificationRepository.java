package com.xetax.crm.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop30ByTargetUserIdOrderByIdDesc(String targetUserId);

    long countByTargetUserIdAndReadAtIsNull(String targetUserId);

    Optional<Notification> findByIdAndTargetUserId(Long id, String targetUserId);

    long deleteByCreatedAtBefore(LocalDateTime cutoff);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.targetUserId = :target AND n.readAt IS NULL")
    int markAllRead(@Param("target") String target, @Param("now") LocalDateTime now);
}
