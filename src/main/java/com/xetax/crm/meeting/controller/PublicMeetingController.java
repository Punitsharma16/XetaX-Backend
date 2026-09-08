package com.xetax.crm.meeting.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.meeting.service.MeetingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Guest-facing: the join page needs the meeting title/status, nothing more. */
@RestController
@RequestMapping("/api/public/meetings")
@RequiredArgsConstructor
public class PublicMeetingController {

    private final MeetingService meetingService;

    @GetMapping("/{roomCode}")
    public ApiResponse<Map<String, Object>> info(@PathVariable String roomCode,
                                                 @RequestParam("t") String token) {
        return ResponseUtil.success("Meeting info", meetingService.publicInfo(roomCode, token));
    }
}
