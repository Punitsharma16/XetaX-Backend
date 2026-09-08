package com.xetax.crm.desk;

import com.xetax.crm.agent.entity.AiAgent;
import com.xetax.crm.agent.repository.AiAgentRepository;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.StageRepo;
import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.notification.NotificationService;
import com.xetax.crm.team.service.PermissionService;
import com.xetax.crm.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** What the floating desk in the panel does: see requests, claim, chat, resolve. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeskService {

    private final ChatSessionRepository sessions;
    private final ChatSessionMessageRepository messages;
    private final HandoffRequestRepository handoffs;
    private final BotConversationService bot;
    private final CurrentUserProvider currentUserProvider;
    private final PermissionService permissionService;
    private final TeamService teamService;
    private final NotificationService notificationService;
    private final RecordService recordService;
    private final StageRepo stageRepo;
    private final AiAgentRepository agentRepository;
    private final ChannelConfigService configService;

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    private String me() {
        UUID id = currentUserProvider.currentUserIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    private String myName() {
        AuthUserEntity u = currentUserProvider.currentUserOrNull();
        if (u == null) return "Team";
        return u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getEmail();
    }

    /** Cheap poll: is there anything for me? */
    public Map<String, Object> badge() {
        String own = owner();
        return Map.of(
                "open", handoffs.countByOwnerUserIdAndStatus(own, "OPEN"),
                "mine", sessions.countByOwnerUserIdAndStatusAndAcceptedBy(own, ChatSession.STATUS_HUMAN, me()),
                "enabled", deskEnabled(own));
    }

    /** Desk shows only when the org has WhatsApp or at least one live agent. */
    private boolean deskEnabled(String own) {
        return agentRepository.findByOwnerUserIdOrderByIdDesc(own).stream().anyMatch(a -> "ACTIVE".equals(a.getStatus()))
                || !configService.whatsappAgentConfig(own).isEmpty();
    }

    public Map<String, Object> state() {
        String own = owner();
        List<Map<String, Object>> requests = new ArrayList<>();
        for (HandoffRequest r : handoffs.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(own, "OPEN")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId()); m.put("sessionId", r.getSessionId()); m.put("channel", r.getChannel());
            m.put("customer", r.getCustomerLabel()); m.put("lastMessage", r.getLastMessage());
            m.put("aiSummary", r.getAiSummary()); m.put("reason", r.getReason());
            m.put("createdAt", r.getCreatedAt()); m.put("escalated", r.isEscalated());
            sessions.findById(r.getSessionId()).ifPresent(s -> m.put("recordId", s.getRecordId()));
            requests.add(m);
        }
        List<Map<String, Object>> mine = new ArrayList<>();
        for (ChatSession s : sessions.findByOwnerUserIdAndStatusAndAcceptedByOrderByLastMessageAtDesc(own, ChatSession.STATUS_HUMAN, me())) {
            mine.add(sessionRow(s));
        }
        // Owner/admin also sees chats other members are handling (read-only unless they take over).
        List<Map<String, Object>> others = new ArrayList<>();
        if (permissionService.isAdmin()) {
            for (ChatSession s : sessions.findByOwnerUserIdAndStatusOrderByLastMessageAtDesc(own, ChatSession.STATUS_HUMAN)) {
                if (!me().equals(s.getAcceptedBy())) others.add(sessionRow(s));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", requests); out.put("mine", mine); out.put("others", others);
        out.put("enabled", deskEnabled(own));
        return out;
    }

    private Map<String, Object> sessionRow(ChatSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId()); m.put("channel", s.getChannel()); m.put("customer", BotConversationService.labelOf(s));
        m.put("phone", s.getCustomerPhone()); m.put("recordId", s.getRecordId()); m.put("contactId", s.getContactId());
        m.put("status", s.getStatus()); m.put("acceptedBy", s.getAcceptedBy());
        m.put("acceptedByName", s.getAcceptedBy() == null ? null : teamService.memberDisplayName(s.getAcceptedBy()));
        m.put("lastMessageAt", s.getLastMessageAt()); m.put("summary", s.getSummary());
        List<ChatSessionMessage> last = messages.findTop40BySessionIdOrderByIdDesc(s.getId());
        m.put("lastMessage", last.isEmpty() ? "" : last.get(0).getText());
        m.put("lastRole", last.isEmpty() ? "" : last.get(0).getRole());
        return m;
    }

    @Transactional
    public Map<String, Object> accept(Long requestId) {
        String own = owner();
        HandoffRequest r = handoffs.findById(requestId)
                .filter(x -> x.getOwnerUserId().equals(own))
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        int claimed = handoffs.claim(requestId, me(), LocalDateTime.now());
        if (claimed == 0) throw new BadRequestException("Someone on your team already took this chat.");

        ChatSession s = sessions.findById(r.getSessionId()).orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        s.setStatus(ChatSession.STATUS_HUMAN); s.setAcceptedBy(me()); s.setAcceptedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sessions.save(s);
        bot.systemMessage(s, myName() + " joined the chat");
        if ("WHATSAPP".equals(s.getChannel())) {
            bot.humanMessage(s, me(), "Hi, this is " + myName() + " from the team — I'm here now, how can I help?");
        }
        return sessionRow(s);
    }

    /** Nobody free right now — give the customer back to the AI, politely. */
    @Transactional
    public void decline(Long requestId) {
        String own = owner();
        HandoffRequest r = handoffs.findById(requestId)
                .filter(x -> x.getOwnerUserId().equals(own) && "OPEN".equals(x.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        r.setStatus("DECLINED"); r.setResolvedAt(LocalDateTime.now()); handoffs.save(r);
        sessions.findById(r.getSessionId()).ifPresent(s -> {
            s.setStatus(ChatSession.STATUS_AI); s.setUpdatedAt(LocalDateTime.now()); sessions.save(s);
            bot.aiMessage(s, "Our team is tied up at the moment — I'll keep helping you here, and they'll follow up as soon as they're free.");
        });
    }

    public Map<String, Object> messages(Long sessionId, Long afterId) {
        ChatSession s = sessions.findByIdAndOwnerUserId(sessionId, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        List<ChatSessionMessage> list;
        if (afterId != null && afterId > 0) {
            list = messages.findTop100BySessionIdAndIdGreaterThanOrderByIdAsc(sessionId, afterId);
        } else {
            list = messages.findTop100BySessionIdOrderByIdDesc(sessionId);
            Collections.reverse(list);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatSessionMessage m : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId()); row.put("role", m.getRole()); row.put("text", m.getText());
            row.put("at", m.getCreatedAt());
            row.put("sender", m.getSenderId() == null ? null : teamService.memberDisplayName(m.getSenderId()));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sessionRow(s)); out.put("messages", rows);
        return out;
    }

    @Transactional
    public Map<String, Object> reply(Long sessionId, String text) {
        ChatSession s = sessions.findByIdAndOwnerUserId(sessionId, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        String body = text == null ? "" : text.trim();
        if (body.isEmpty() || body.length() > 4000) throw new BadRequestException("Message is empty or too long");
        if (!ChatSession.STATUS_HUMAN.equals(s.getStatus())) {
            // Taking over an AI/waiting chat directly from the desk: claim it on the fly.
            handoffs.findFirstBySessionIdAndStatus(s.getId(), "OPEN").ifPresent(r -> handoffs.claim(r.getId(), me(), LocalDateTime.now()));
            s.setStatus(ChatSession.STATUS_HUMAN); s.setAcceptedBy(me()); s.setAcceptedAt(LocalDateTime.now());
            sessions.save(s);
            bot.systemMessage(s, myName() + " joined the chat");
        } else if (!me().equals(s.getAcceptedBy()) && !permissionService.isAdmin()) {
            throw new BadRequestException("This chat is being handled by " + teamService.memberDisplayName(s.getAcceptedBy()));
        }
        ChatSessionMessage m = bot.humanMessage(s, me(), body);
        return Map.of("id", m.getId(), "at", m.getCreatedAt());
    }

    @Transactional
    public void resolve(Long sessionId, boolean resumeAi) {
        ChatSession s = sessions.findByIdAndOwnerUserId(sessionId, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        handoffs.findFirstBySessionIdAndStatus(s.getId(), "ACCEPTED").ifPresent(r -> {
            r.setStatus("RESOLVED"); r.setResolvedAt(LocalDateTime.now()); handoffs.save(r);
        });
        handoffs.findFirstBySessionIdAndStatus(s.getId(), "OPEN").ifPresent(r -> {
            r.setStatus("RESOLVED"); r.setResolvedAt(LocalDateTime.now()); handoffs.save(r);
        });
        s.setStatus(resumeAi ? ChatSession.STATUS_AI : ChatSession.STATUS_RESOLVED);
        s.setAcceptedBy(null); s.setAiTurns(0); s.setUpdatedAt(LocalDateTime.now());
        sessions.save(s);
        bot.systemMessage(s, resumeAi ? "Handed back to the assistant" : "Chat closed by " + myName());
        summarizeLater(s.getId());
    }

    @Async("botExecutor")
    public void summarizeLater(Long sessionId) {
        try {
            sessions.findById(sessionId).ifPresent(bot::summarize);
        } catch (Exception e) {
            log.warn("Summary after resolve failed for {}: {}", sessionId, e.getMessage());
        }
    }

    @Transactional
    public void assign(Long sessionId, String userId) {
        ChatSession s = sessions.findByIdAndOwnerUserId(sessionId, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        if (!teamService.isInMyOrg(userId)) throw new BadRequestException("That user is not in your team");
        s.setStatus(ChatSession.STATUS_HUMAN); s.setAcceptedBy(userId); s.setAcceptedAt(LocalDateTime.now());
        sessions.save(s);
        handoffs.findFirstBySessionIdAndStatus(s.getId(), "OPEN").ifPresent(r -> {
            r.setStatus("ACCEPTED"); r.setAcceptedBy(userId); r.setAcceptedAt(LocalDateTime.now()); handoffs.save(r);
        });
        bot.systemMessage(s, "Assigned to " + teamService.memberDisplayName(userId) + " by " + myName());
        notificationService.push(owner(), userId, "HANDOFF", "A chat was assigned to you: " + BotConversationService.labelOf(s),
                null, "/app/dashboard?desk=open");
    }

    /** Record page: latest bot conversation, its summary and any pending AI suggestion. */
    public Map<String, Object> recordAi(String recordId) {
        String own = owner();
        List<ChatSession> list = sessions.findTop5ByRecordIdAndOwnerUserIdOrderByIdDesc(recordId, own);
        Map<String, Object> out = new LinkedHashMap<>();
        if (list.isEmpty()) { out.put("session", null); return out; }
        ChatSession s = list.get(0);
        Map<String, Object> row = sessionRow(s);
        row.put("summaryAt", s.getSummaryAt());
        if (s.getPendingStageId() != null) {
            row.put("pendingStageId", s.getPendingStageId());
            row.put("pendingStageName", stageRepo.findById(s.getPendingStageId()).map(FormStage::getName).orElse("?"));
        }
        out.put("session", row);
        return out;
    }

    /** SUGGEST mode: a human approves the AI's proposed move (normal guards apply). */
    @Transactional
    public Map<String, Object> applySuggestion(Long sessionId) {
        ChatSession s = sessions.findByIdAndOwnerUserId(sessionId, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        if (s.getRecordId() == null || s.getPendingStageId() == null) throw new BadRequestException("Nothing to apply");
        recordService.changeStage(s.getRecordId(), s.getPendingStageId());
        s.setPendingStageId(null); s.setPendingStatusId(null); sessions.save(s);
        return Map.of("applied", true);
    }

    @Transactional
    public void dismissSuggestion(Long sessionId) {
        ChatSession s = sessions.findByIdAndOwnerUserId(sessionId, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        s.setPendingStageId(null); s.setPendingStatusId(null); sessions.save(s);
    }

    /** Agents this owner has — used by the record card to explain where the chat came from. */
    public String agentName(Long agentId) {
        return agentRepository.findById(agentId).map(AiAgent::getName).orElse("Bot");
    }
}
