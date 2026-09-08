package com.xetax.crm.meeting.repository;

import com.xetax.crm.meeting.entity.Meeting;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    Optional<Meeting> findByRoomCode(String roomCode);

    Optional<Meeting> findByIdAndOwnerUserId(Long id, String ownerUserId);

    Optional<Meeting> findByRoomCodeAndOwnerUserId(String roomCode, String ownerUserId);

    Page<Meeting> findByOwnerUserIdOrderByIdDesc(String ownerUserId, Pageable pageable);

    long countByOwnerUserIdAndStatusIn(String ownerUserId, java.util.List<String> statuses);

    java.util.List<Meeting> findTop3ByOwnerUserIdAndStatusInOrderByScheduledAtAsc(String ownerUserId, java.util.List<String> statuses);

}
