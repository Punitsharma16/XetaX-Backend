package com.xetax.crm.meeting.repository;

import com.xetax.crm.meeting.entity.MeetingNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingNoteRepository extends JpaRepository<MeetingNote, Long> {

    List<MeetingNote> findByMeetingIdOrderByIdAsc(Long meetingId);
}
