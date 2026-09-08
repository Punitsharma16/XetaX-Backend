package com.xetax.crm.meeting.service;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.email.EmailService;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.exception.UnauthorizedException;
import com.xetax.crm.meeting.dto.*;
import com.xetax.crm.meeting.entity.Meeting;
import com.xetax.crm.meeting.entity.MeetingInvitee;
import com.xetax.crm.meeting.entity.MeetingNote;
import com.xetax.crm.meeting.repository.MeetingInviteeRepository;
import com.xetax.crm.meeting.repository.MeetingNoteRepository;
import com.xetax.crm.meeting.repository.MeetingRepository;
import com.xetax.crm.whatsapp.dto.SendMessageRequest;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Video meetings: instant or scheduled, optionally linked to a CRM record.
 * Owner-scoped everywhere; guests only ever get roomCode + guestToken via the
 * shared link. No media is recorded — only the owner's notes are stored.
 */
@Slf4j
@Service
public class MeetingService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final MeetingRepository meetingRepository;
    private final MeetingNoteRepository noteRepository;
    private final MeetingInviteeRepository inviteeRepository;
    private final CurrentUserProvider currentUserProvider;
    private final EmailService emailService;
    private final OrgSmtpService orgSmtpService;
    private final WhatsAppMessagingService whatsAppMessagingService;
    private final String publicBaseUrl;

    public MeetingService(MeetingRepository meetingRepository,
                          MeetingNoteRepository noteRepository,
                          MeetingInviteeRepository inviteeRepository,
                          CurrentUserProvider currentUserProvider,
                          EmailService emailService,
                          OrgSmtpService orgSmtpService,
                          @Lazy WhatsAppMessagingService whatsAppMessagingService,
                          @Value("${app.public-base-url}") String publicBaseUrl) {
        this.meetingRepository = meetingRepository;
        this.noteRepository = noteRepository;
        this.inviteeRepository = inviteeRepository;
        this.currentUserProvider = currentUserProvider;
        this.emailService = emailService;
        this.orgSmtpService = orgSmtpService;
        this.whatsAppMessagingService = whatsAppMessagingService;
        this.publicBaseUrl = publicBaseUrl;
    }

    private String currentUserId() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new UnauthorizedException("Not authenticated");
        return id.toString();
    }

    /* ------------------------------------------------------------- create */

    @Transactional
    public MeetingResponse create(MeetingCreateRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new BadRequestException("Meeting title is required");
        }
        if (request.getScheduledAt() != null && request.getScheduledAt().isBefore(Instant.now())) {
            throw new BadRequestException("Scheduled time must be in the future");
        }
        Meeting meeting = Meeting.builder()
                .ownerUserId(currentUserId())
                .title(request.getTitle().trim())
                .roomCode(generateRoomCode())
                .guestToken(token())
                .hostToken(token())
                .recordId(blankToNull(request.getRecordId()))
                .guestName(blankToNull(request.getGuestName()))
                .guestPhone(blankToNull(request.getGuestPhone()))
                .guestEmail(blankToNull(request.getGuestEmail()))
                .scheduledAt(request.getScheduledAt())
                .status("SCHEDULED")
                .build();
        meeting = meetingRepository.save(meeting);

        List<MeetingCreateRequest.InviteeRequest> incoming = new ArrayList<>();
        if (meeting.getGuestName() != null || meeting.getGuestPhone() != null
                || meeting.getGuestEmail() != null) {
            MeetingCreateRequest.InviteeRequest primary = new MeetingCreateRequest.InviteeRequest();
            primary.setName(meeting.getGuestName());
            primary.setPhone(meeting.getGuestPhone());
            primary.setEmail(meeting.getGuestEmail());
            incoming.add(primary);
        }
        if (request.getInvitees() != null) incoming.addAll(request.getInvitees());

        for (MeetingCreateRequest.InviteeRequest invitee : incoming) {
            String name = blankToNull(invitee.getName());
            String phone = blankToNull(invitee.getPhone());
            String email = blankToNull(invitee.getEmail());
            if (name == null && phone == null && email == null) continue;
            inviteeRepository.save(MeetingInvitee.builder()
                    .meetingId(meeting.getId()).name(name).phone(phone).email(email).build());
        }
        // Card display convenience: mirror the first invitee onto the meeting.
        List<MeetingInvitee> saved = inviteeRepository.findByMeetingIdOrderByIdAsc(meeting.getId());
        if (!saved.isEmpty() && meeting.getGuestName() == null && meeting.getGuestPhone() == null
                && meeting.getGuestEmail() == null) {
            meeting.setGuestName(saved.get(0).getName());
            meeting.setGuestPhone(saved.get(0).getPhone());
            meeting.setGuestEmail(saved.get(0).getEmail());
            meeting = meetingRepository.save(meeting);
        }
        return toResponse(meeting);
    }

    /* --------------------------------------------------------------- read */

    public Page<MeetingResponse> list(int page, int size) {
        return meetingRepository.findByOwnerUserIdOrderByIdDesc(currentUserId(),
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))
                .map(this::toResponse);
    }

    public MeetingResponse get(Long id) {
        return toResponse(requireMine(id));
    }

    /** Owner opening the room page — resolves by code, proves ownership. */
    public MeetingResponse getMineByCode(String roomCode) {
        Meeting meeting = meetingRepository
                .findByRoomCodeAndOwnerUserId(roomCode, currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Meeting not found"));
        return toResponse(meeting);
    }

    /** Public join-page info — token must match; nothing sensitive returned. */
    public Map<String, Object> publicInfo(String roomCode, String token) {
        Meeting meeting = meetingRepository.findByRoomCode(roomCode)
                .filter(m -> m.getGuestToken().equals(token) || m.getHostToken().equals(token))
                .orElseThrow(() -> new ResourceNotFoundException("Meeting not found"));
        return Map.of(
                "title", meeting.getTitle(),
                "status", meeting.getStatus(),
                "scheduledAt", meeting.getScheduledAt() == null ? "" : meeting.getScheduledAt().toString(),
                "hostName", "XetaX host"
        );
    }

    /* ------------------------------------------------------------ actions */

    @Transactional
    public MeetingResponse cancel(Long id) {
        Meeting meeting = requireMine(id);
        if ("ENDED".equals(meeting.getStatus())) {
            throw new BadRequestException("The meeting has already ended");
        }
        meeting.setStatus("CANCELLED");
        return toResponse(meetingRepository.save(meeting));
    }

    @Transactional
    public MeetingResponse end(Long id) {
        Meeting meeting = requireMine(id);
        meeting.setStatus("ENDED");
        meeting.setEndedAt(Instant.now());
        return toResponse(meetingRepository.save(meeting));
    }

    /** Called by the signaling layer on first join. Not user-facing. */
    @Transactional
    public void markLive(String roomCode) {
        meetingRepository.findByRoomCode(roomCode).ifPresent(meeting -> {
            if ("SCHEDULED".equals(meeting.getStatus())) {
                meeting.setStatus("LIVE");
                meeting.setStartedAt(Instant.now());
                meetingRepository.save(meeting);
            }
        });
    }

    /** Signaling-layer join guard: token + not cancelled/ended. */
    public boolean canJoin(String roomCode, String token) {
        return meetingRepository.findByRoomCode(roomCode)
                .filter(m -> !"CANCELLED".equals(m.getStatus()) && !"ENDED".equals(m.getStatus()))
                .map(m -> m.getGuestToken().equals(token) || m.getHostToken().equals(token))
                .orElse(false);
    }

    /* -------------------------------------------------------------- share */

    /**
     * Sends the guest link to invitees. inviteeId null => everyone who has
     * the needed contact for that channel. WHATSAPP goes through the normal
     * send pipeline (24h-window rule applies); EMAIL requires SMTP to be
     * configured — otherwise it fails loudly instead of pretending.
     */
    public Map<String, Object> share(Long id, String channel, Long inviteeId) {
        Meeting meeting = requireMine(id);
        boolean whatsapp = "WHATSAPP".equalsIgnoreCase(channel);
        boolean email = "EMAIL".equalsIgnoreCase(channel);
        if (!whatsapp && !email) {
            throw new BadRequestException("channel must be WHATSAPP or EMAIL");
        }
        if (email && !orgSmtpService.isConfiguredFor(meeting.getOwnerUserId())) {
            throw new BadRequestException("Email abhi configured nahi hai — Profile → Email "
                    + "settings me apna SMTP (Gmail app-password etc.) add karo, "
                    + "ya WhatsApp/copy-link use karo.");
        }

        List<MeetingInvitee> targets = inviteeRepository
                .findByMeetingIdOrderByIdAsc(meeting.getId()).stream()
                .filter(i -> inviteeId == null || i.getId().equals(inviteeId))
                .filter(i -> whatsapp ? i.getPhone() != null : i.getEmail() != null)
                .toList();
        if (targets.isEmpty()) {
            throw new BadRequestException(whatsapp
                    ? "Kisi invitee ka phone number nahi hai"
                    : "Kisi invitee ka email nahi hai");
        }

        List<String> sent = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();
        for (MeetingInvitee invitee : targets) {
            String text = inviteText(meeting, invitee.getName());
            try {
                if (whatsapp) {
                    SendMessageRequest send = new SendMessageRequest();
                    send.setPhone(invitee.getPhone());
                    send.setMessage(text);
                    whatsAppMessagingService.send(send);
                    sent.add(invitee.getPhone());
                } else {
                    orgSmtpService.sendAs(meeting.getOwnerUserId(), invitee.getEmail(),
                            "Meeting invite: " + meeting.getTitle(), text);
                    sent.add(invitee.getEmail());
                }
            } catch (Exception e) {
                Map<String, String> failure = new LinkedHashMap<>();
                failure.put("to", whatsapp ? invitee.getPhone() : invitee.getEmail());
                failure.put("reason", e.getMessage() == null ? "send failed" : e.getMessage());
                failed.add(failure);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", whatsapp ? "WHATSAPP" : "EMAIL");
        result.put("sentCount", sent.size());
        result.put("sent", sent);
        result.put("failed", failed);
        return result;
    }

    private String inviteText(Meeting meeting, String inviteeName) {
        String when = meeting.getScheduledAt() == null
                ? "abhi (instant meeting)"
                : DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")
                    .withZone(ZoneId.systemDefault()).format(meeting.getScheduledAt());
        return "Hi" + (inviteeName == null ? "" : " " + inviteeName)
                + "! You are invited to a video meeting: \"" + meeting.getTitle() + "\"\n"
                + "When: " + when + "\n"
                + "Join link: " + guestLink(meeting);
    }

    /* -------------------------------------------------------------- notes */

    public List<MeetingNoteResponse> notes(Long meetingId) {
        requireMine(meetingId);
        return noteRepository.findByMeetingIdOrderByIdAsc(meetingId).stream()
                .map(n -> MeetingNoteResponse.builder()
                        .id(n.getId()).content(n.getContent()).createdAt(n.getCreatedAt()).build())
                .toList();
    }

    @Transactional
    public MeetingNoteResponse addNote(Long meetingId, String content) {
        Meeting meeting = requireMine(meetingId);
        if (content == null || content.isBlank()) {
            throw new BadRequestException("Note content is required");
        }
        MeetingNote note = noteRepository.save(MeetingNote.builder()
                .meetingId(meeting.getId())
                .ownerUserId(meeting.getOwnerUserId())
                .content(content.trim())
                .build());
        return MeetingNoteResponse.builder()
                .id(note.getId()).content(note.getContent()).createdAt(note.getCreatedAt()).build();
    }

    @Transactional
    public void deleteNote(Long meetingId, Long noteId) {
        requireMine(meetingId);
        noteRepository.findById(noteId)
                .filter(n -> n.getMeetingId().equals(meetingId))
                .ifPresent(noteRepository::delete);
    }

    /* ------------------------------------------------------------ helpers */

    private Meeting requireMine(Long id) {
        return meetingRepository.findByIdAndOwnerUserId(id, currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Meeting not found"));
    }

    private String generateRoomCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                if (i == 3 || i == 7) code.append('-');
                code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            if (meetingRepository.findByRoomCode(code.toString()).isEmpty()) {
                return code.toString();
            }
        }
        return UUID.randomUUID().toString().substring(0, 12);
    }

    private String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String guestLink(Meeting meeting) {
        return publicBaseUrl + "/meet/" + meeting.getRoomCode() + "?t=" + meeting.getGuestToken();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MeetingResponse toResponse(Meeting meeting) {
        return MeetingResponse.builder()
                .id(meeting.getId())
                .title(meeting.getTitle())
                .roomCode(meeting.getRoomCode())
                .status(meeting.getStatus())
                .scheduledAt(meeting.getScheduledAt())
                .startedAt(meeting.getStartedAt())
                .endedAt(meeting.getEndedAt())
                .recordId(meeting.getRecordId())
                .guestName(meeting.getGuestName())
                .guestPhone(meeting.getGuestPhone())
                .guestEmail(meeting.getGuestEmail())
                .guestLink(guestLink(meeting))
                .hostLink(publicBaseUrl + "/meet/" + meeting.getRoomCode() + "?t=" + meeting.getHostToken())
                .createdAt(meeting.getCreatedAt())
                .noteCount(noteRepository.findByMeetingIdOrderByIdAsc(meeting.getId()).size())
                .invitees(inviteeRepository.findByMeetingIdOrderByIdAsc(meeting.getId()).stream()
                        .map(i -> MeetingResponse.InviteeResponse.builder()
                                .id(i.getId()).name(i.getName())
                                .phone(i.getPhone()).email(i.getEmail()).build())
                        .toList())
                .build();
    }
}
