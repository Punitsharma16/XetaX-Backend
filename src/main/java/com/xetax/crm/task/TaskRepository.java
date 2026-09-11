package com.xetax.crm.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<TaskItem, Long> {

    List<TaskItem> findTop100ByAssignedToAndStatusOrderByDueAtAsc(String assignedTo, String status);

    List<TaskItem> findTop50ByContactIdAndOwnerUserIdOrderByStatusDescDueAtAsc(Long contactId, String owner);

    List<TaskItem> findTop50ByRecordIdAndOwnerUserIdOrderByStatusDescDueAtAsc(String recordId, String owner);

    Optional<TaskItem> findByIdAndOwnerUserId(Long id, String ownerUserId);

    List<TaskItem> findTop100ByStatusAndReminderSentFalseAndDueAtBefore(String status, LocalDateTime before);

    long countByAssignedToAndStatusAndDueAtBetween(String assignedTo, String status,
                                                   LocalDateTime from, LocalDateTime to);

    long countByAssignedToAndStatusAndDueAtLessThan(String assignedTo, String status, LocalDateTime before);
}
