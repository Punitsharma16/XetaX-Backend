package com.xetax.crm.meeting.controller;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.meeting.dto.*;
import com.xetax.crm.meeting.service.MeetingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;

    @PostMapping
    @RequiresPermission("meetings.manage")
    public ApiResponse<MeetingResponse> create(@RequestBody MeetingCreateRequest request) {
        return ResponseUtil.success("Meeting created", meetingService.create(request));
    }

    @GetMapping
    @RequiresPermission("meetings.view")
    public ApiResponse<Page<MeetingResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseUtil.success("Meetings", meetingService.list(page, size));
    }

    @GetMapping("/{id}")
    @RequiresPermission("meetings.view")
    public ApiResponse<MeetingResponse> get(@PathVariable Long id) {
        return ResponseUtil.success("Meeting", meetingService.get(id));
    }

    /** Room page uses this to know "am I the host?" and get the meeting id. */
    @GetMapping("/by-code/{roomCode}")
    @RequiresPermission("meetings.view")
    public ApiResponse<MeetingResponse> byCode(@PathVariable String roomCode) {
        return ResponseUtil.success("Meeting", meetingService.getMineByCode(roomCode));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission("meetings.manage")
    public ApiResponse<MeetingResponse> cancel(@PathVariable Long id) {
        return ResponseUtil.success("Meeting cancelled", meetingService.cancel(id));
    }

    @PostMapping("/{id}/end")
    @RequiresPermission("meetings.manage")
    public ApiResponse<MeetingResponse> end(@PathVariable Long id) {
        return ResponseUtil.success("Meeting ended", meetingService.end(id));
    }

    /** body: {"channel": "WHATSAPP"|"EMAIL", "inviteeId": optional — absent = all invitees}. */
    @PostMapping("/{id}/share")
    @RequiresPermission("meetings.manage")
    public ApiResponse<Map<String, Object>> share(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> body) {
        String channel = String.valueOf(body.getOrDefault("channel", ""));
        Long inviteeId = body.get("inviteeId") == null
                ? null : Long.valueOf(String.valueOf(body.get("inviteeId")));
        return ResponseUtil.success("Invite sent", meetingService.share(id, channel, inviteeId));
    }

    /* ------------------------------------------------------------- notes */

    @GetMapping("/{id}/notes")
    @RequiresPermission("meetings.manage")
    public ApiResponse<List<MeetingNoteResponse>> notes(@PathVariable Long id) {
        return ResponseUtil.success("Notes", meetingService.notes(id));
    }

    @PostMapping("/{id}/notes")
    @RequiresPermission("meetings.manage")
    public ApiResponse<MeetingNoteResponse> addNote(@PathVariable Long id,
                                                    @RequestBody Map<String, String> body) {
        return ResponseUtil.success("Note saved",
                meetingService.addNote(id, body.getOrDefault("content", "")));
    }

    @DeleteMapping("/{id}/notes/{noteId}")
    @RequiresPermission("meetings.manage")
    public ApiResponse<Void> deleteNote(@PathVariable Long id, @PathVariable Long noteId) {
        meetingService.deleteNote(id, noteId);
        return ResponseUtil.success("Note deleted");
    }
}
