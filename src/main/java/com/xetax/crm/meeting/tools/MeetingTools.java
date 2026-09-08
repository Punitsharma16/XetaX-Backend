package com.xetax.crm.meeting.tools;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.meeting.dto.MeetingCreateRequest;
import com.xetax.crm.meeting.dto.MeetingResponse;
import com.xetax.crm.meeting.service.MeetingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI tools for video meetings. Same rules as every other tool set:
 * the acting user comes from the SecurityContext, tokens/links belong to
 * that user only, and outward actions (sending the invite) happen ONLY on
 * an explicit user request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingTools {

    private final MeetingService meetingService;

    @Tool(description = "List the user's video meetings with status, time and guest info.")
    @RequiresPermission("meetings.view")
    public List<Map<String, Object>> getMyMeetings() {
        try {
            return meetingService.list(0, 20).getContent().stream()
                    .map(MeetingTools::meetingMap)
                    .toList();
        } catch (Exception e) {
            return List.of(Map.of("error", "Could not read meetings right now."));
        }
    }

    @Tool(description = "Create a video meeting (instant or scheduled) and get its share link. "
            + "scheduledAtIso is optional ISO-8601 UTC like 2026-08-22T10:30:00Z — omit it for an "
            + "instant meeting. Guest phone/email are optional but needed later to send the "
            + "invite. This only CREATES the meeting; it never sends the link by itself.")
    @RequiresPermission("meetings.manage")
    public Map<String, Object> createMeeting(
            @ToolParam(description = "Meeting title, e.g. 'Site visit discussion'") String title,
            @ToolParam(description = "Guest's name (optional)", required = false) String guestName,
            @ToolParam(description = "Guest phone with country code (optional)", required = false) String guestPhone,
            @ToolParam(description = "Guest email (optional)", required = false) String guestEmail,
            @ToolParam(description = "ISO-8601 UTC start time; omit for instant", required = false) String scheduledAtIso) {
        try {
            MeetingCreateRequest request = new MeetingCreateRequest();
            request.setTitle(title);
            request.setGuestName(guestName);
            request.setGuestPhone(guestPhone);
            request.setGuestEmail(guestEmail);
            if (scheduledAtIso != null && !scheduledAtIso.isBlank()) {
                request.setScheduledAt(Instant.parse(scheduledAtIso.trim()));
            }
            MeetingResponse created = meetingService.create(request);
            Map<String, Object> out = meetingMap(created);
            out.put("note", "Meeting created. Ask me to send the link via WhatsApp or email, "
                    + "or share the guestLink yourself.");
            return out;
        } catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = "Send a meeting's invite link to ALL its invitees. channel must be WHATSAPP "
            + "(needs guest phone + user's WhatsApp connected) or EMAIL (needs guest email). "
            + "Use ONLY when the user explicitly asks to send the link.")
    @RequiresPermission("meetings.manage")
    public Map<String, Object> sendMeetingLink(
            @ToolParam(description = "Meeting id from getMyMeetings/createMeeting") Long meetingId,
            @ToolParam(description = "WHATSAPP or EMAIL") String channel) {
        try {
            return new LinkedHashMap<>(meetingService.share(meetingId, channel, null));
        } catch (Exception e) {
            return Map.of("sent", false, "error", safeMessage(e));
        }
    }

    @Tool(description = "Cancel one of the user's upcoming meetings by id.")
    @RequiresPermission("meetings.manage")
    public Map<String, Object> cancelMeeting(
            @ToolParam(description = "Meeting id") Long meetingId) {
        try {
            MeetingResponse cancelled = meetingService.cancel(meetingId);
            return Map.of("cancelled", true, "title", cancelled.getTitle());
        } catch (Exception e) {
            return Map.of("cancelled", false, "error", safeMessage(e));
        }
    }

    private static Map<String, Object> meetingMap(MeetingResponse meeting) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", meeting.getId());
        map.put("title", meeting.getTitle());
        map.put("status", meeting.getStatus());
        map.put("scheduledAt", meeting.getScheduledAt() == null
                ? "instant" : meeting.getScheduledAt().toString());
        map.put("guestName", meeting.getGuestName());
        map.put("guestPhone", meeting.getGuestPhone());
        map.put("guestEmail", meeting.getGuestEmail());
        map.put("guestLink", meeting.getGuestLink());
        return map;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? "The meeting action could not be completed." : message;
    }
}
