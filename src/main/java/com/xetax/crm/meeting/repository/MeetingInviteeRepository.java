package com.xetax.crm.meeting.repository;

import com.xetax.crm.meeting.entity.MeetingInvitee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingInviteeRepository extends JpaRepository<MeetingInvitee, Long> {

    List<MeetingInvitee> findByMeetingIdOrderByIdAsc(Long meetingId);
}
