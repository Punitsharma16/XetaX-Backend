package com.xetax.crm.playbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.agent.entity.AiAgent;
import com.xetax.crm.agent.repository.AiAgentRepository;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.desk.BotConversationService;
import com.xetax.crm.desk.ChatSession;
import com.xetax.crm.whatsapp.repository.WhatsAppConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Evaluates every active playbook once a minute. Per rule it pulls the
 * candidate records straight from Mongo (form + stage filter), checks the
 * trigger against the record's own timestamps and the customer's last
 * message, then hands the record to {@link PlaybookActions}. Every fired
 * rule is remembered in {@link PlaybookRun} so it never repeats by accident.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybookEngine {

    /** Records examined per rule per tick — keeps one tenant from hogging the scheduler. */
    private static final int CANDIDATES_PER_RULE = 300;
    /** Actions fired per playbook per tick — a gentle drip, never a burst. */
    private static final int ACTIONS_PER_TICK = 40;
    private static final List<String> MESSAGE_CHANNELS = List.of(
            MessageDeliveryService.WHATSAPP, MessageDeliveryService.WHATSAPP_TEMPLATE, MessageDeliveryService.EMAIL);

    private final SalesPlaybookRepository playbooks;
    private final PlaybookRunRepository runs;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final RecordRepo recordRepo;
    private final MongoTemplate mongoTemplate;
    private final AiAgentRepository agentRepository;
    private final BotConversationService bot;
    private final WhatsAppConversationRepository waConversations;
    private final PlaybookActions actions;
    private final ObjectMapper mapper = new ObjectMapper();

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void tick() {
        for (SalesPlaybook pb : playbooks.findByActiveTrue()) {
            try {
                evaluate(pb, false);
            } catch (Exception e) {
                log.warn("Playbook {} tick failed: {}", pb.getId(), e.getMessage());
            }
        }
        fireScheduled();
    }

    /** Result of one evaluation: rule id → how many records matched (and, when live, were actioned). */
    public record RuleStat(String ruleId, String ruleName, int matched, int fired, String note) {}

    public List<RuleStat> evaluate(SalesPlaybook pb, boolean dryRun) {
        List<RuleStat> stats = new ArrayList<>();
        FormEntity form = formRepo.findById(pb.getFormId()).orElse(null);
        if (form == null || !pb.getOwnerUserId().equals(form.getOwnerUserId())) return stats;
        List<PlaybookRule> rules = rulesOf(pb);
        if (rules.isEmpty()) return stats;

        List<FormField> fields = formMetaCache.getFields(form.getId());
        List<FormStage> stages = formMetaCache.getStages(form.getId());
        Set<Long> finalStages = new HashSet<>();
        for (FormStage s : stages) if (Boolean.TRUE.equals(s.getIsFinal())) finalStages.add(s.getId());
        AiAgent agent = pb.getAgentId() == null ? null
                : agentRepository.findByIdAndOwnerUserId(pb.getAgentId(), pb.getOwnerUserId()).orElse(null);
        PlaybookActions.Context ctx = new PlaybookActions.Context(pb, form, fields, stages, agent, null);
        boolean quiet = inQuietHours(pb);
        LocalDateTime now = LocalDateTime.now();
        int fired = 0;

        for (PlaybookRule rule : rules) {
            if (!rule.isActive() || rule.getId() == null) continue;
            int matched = 0, ruleFired = 0;
            String note = quiet && isOutbound(rule) ? "Quiet hours — waiting" : null;
            for (RecordDocument record : candidates(form.getId(), rule, stages, finalStages)) {
                PlaybookRun run = runs.findFirstByPlaybookIdAndRuleIdAndRecordIdOrderByIdDesc(pb.getId(), rule.getId(), record.getId()).orElse(null);
                if (!eligible(rule, run, now)) continue;
                if (!conditionsPass(rule, record)) continue;
                if (!triggerFires(pb, rule, record, run, now)) continue;
                if (isOutbound(rule)) {
                    if (humanDriving(pb, record)) continue;
                    if (isMessage(rule) && runs.countByPlaybookIdAndRecordIdAndLastChannelIn(pb.getId(), record.getId(), MESSAGE_CHANNELS) >= pb.getMaxFollowUps()) continue;
                }
                matched++;
                if (dryRun || (quiet && isOutbound(rule)) || fired >= ACTIONS_PER_TICK) continue;
                fire(ctx, rule, record, run, null);
                fired++; ruleFired++;
            }
            stats.add(new RuleStat(rule.getId(), rule.getName(), matched, ruleFired, note));
        }
        return stats;
    }

    /** One-off follow-ups the assistant booked from a chat ("remind them Thursday"). */
    private void fireScheduled() {
        LocalDateTime now = LocalDateTime.now();
        for (PlaybookRun run : runs.findTop50ByStatusAndNextEligibleAtBefore(PlaybookRun.ACTIVE, now)) {
            try {
                SalesPlaybook pb = playbooks.findById(run.getPlaybookId()).orElse(null);
                if (pb == null || !pb.isActive()) { run.setStatus(PlaybookRun.STOPPED); runs.save(run); continue; }
                if (inQuietHours(pb)) continue; // stays queued until the morning
                FormEntity form = formRepo.findById(pb.getFormId()).orElse(null);
                RecordDocument record = recordRepo.findById(run.getRecordId()).orElse(null);
                if (form == null || record == null) { run.setStatus(PlaybookRun.STOPPED); runs.save(run); continue; }
                if (humanDriving(pb, record)) { run.setNextEligibleAt(now.plusHours(2)); runs.save(run); continue; }
                AiAgent agent = pb.getAgentId() == null ? null
                        : agentRepository.findByIdAndOwnerUserId(pb.getAgentId(), pb.getOwnerUserId()).orElse(null);
                PlaybookActions.Context ctx = new PlaybookActions.Context(pb, form,
                        formMetaCache.getFields(form.getId()), formMetaCache.getStages(form.getId()), agent, null);
                PlaybookRule rule = new PlaybookRule();
                rule.setId(run.getRuleId()); rule.setName(run.getRuleName() == null ? "Booked follow-up" : run.getRuleName());
                rule.setAction("SEND_MESSAGE"); rule.setAiCompose(agent != null); rule.setMaxRepeats(1);
                run.setNextEligibleAt(null);
                fire(ctx, rule, record, run, run.getPayload());
            } catch (Exception e) {
                log.warn("Scheduled playbook run {} failed: {}", run.getId(), e.getMessage());
                run.setStatus(PlaybookRun.STOPPED); run.setLastOutcome(MessageDeliveryService.trim(e.getMessage(), 300)); runs.save(run);
            }
        }
    }

    /* ----------------------------------------------------------- matching */

    private List<RecordDocument> candidates(Long formId, PlaybookRule rule, List<FormStage> stages, Set<Long> finalStages) {
        List<Long> stageIds = new ArrayList<>();
        if (rule.getStageIds() != null && !rule.getStageIds().isEmpty()) {
            for (FormStage s : stages) if (rule.getStageIds().contains(s.getId())) stageIds.add(s.getId());
        } else {
            for (FormStage s : stages) if (!finalStages.contains(s.getId())) stageIds.add(s.getId());
        }
        if (stageIds.isEmpty()) return List.of();
        Query q = new Query(Criteria.where("formId").is(formId).and("stageId").in(stageIds))
                .with(Sort.by(Sort.Direction.ASC, "updatedAt")).limit(CANDIDATES_PER_RULE);
        return mongoTemplate.find(q, RecordDocument.class);
    }

    private boolean eligible(PlaybookRule rule, PlaybookRun run, LocalDateTime now) {
        if (run == null) return true;
        if (PlaybookRun.STOPPED.equals(run.getStatus())) return false;
        int max = rule.getMaxRepeats() == null || rule.getMaxRepeats() < 1 ? 1 : rule.getMaxRepeats();
        if (run.getRunCount() >= max) return false;
        int gap = rule.getRepeatEveryMinutes() != null && rule.getRepeatEveryMinutes() > 0 ? rule.getRepeatEveryMinutes()
                : rule.getAfterMinutes() != null && rule.getAfterMinutes() > 0 ? rule.getAfterMinutes() : 1440;
        return run.getLastRunAt() == null || !run.getLastRunAt().plusMinutes(gap).isAfter(now);
    }

    private boolean triggerFires(SalesPlaybook pb, PlaybookRule rule, RecordDocument record, PlaybookRun run, LocalDateTime now) {
        int after = rule.getAfterMinutes() == null || rule.getAfterMinutes() < 0 ? 0 : rule.getAfterMinutes();
        String trigger = rule.getTrigger() == null ? "" : rule.getTrigger();
        switch (trigger) {
            case "RECORD_CREATED": {
                LocalDateTime created = record.getCreatedAt() == null ? now : record.getCreatedAt();
                return !created.plusMinutes(after).isAfter(now);
            }
            case "STAGE_IDLE": {
                LocalDateTime since = record.getUpdatedAt() == null ? record.getCreatedAt() : record.getUpdatedAt();
                if (since == null) since = now;
                return !since.plusMinutes(after).isAfter(now);
            }
            case "NO_REPLY": {
                LocalDateTime lastCustomer = lastCustomerAt(pb, record);
                if (lastCustomer.plusMinutes(after).isAfter(now)) return false;
                // silent since our last touch too (nothing new to react to)
                return run == null || run.getLastRunAt() == null || lastCustomer.isBefore(run.getLastRunAt())
                        || !run.getLastRunAt().plusMinutes(after).isAfter(now);
            }
            case "QUALIFIED": {
                List<String> keys = qualificationKeys(pb);
                if (keys.isEmpty()) return false;
                Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
                for (String k : keys) {
                    Object v = data.get(k);
                    if (v == null || String.valueOf(v).isBlank()) return false;
                }
                return true;
            }
            case "INTEREST": {
                String want = rule.getInterest() == null ? "" : rule.getInterest().trim().toUpperCase();
                if (want.isEmpty()) return false;
                Object summary = record.getData() == null ? null : record.getData().get("ai_summary");
                String s = summary == null ? "" : String.valueOf(summary).toUpperCase();
                return s.contains("INTEREST: " + want);
            }
            default:
                return false;
        }
    }

    private boolean conditionsPass(PlaybookRule rule, RecordDocument record) {
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) return true;
        Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
        for (PlaybookRule.Condition c : rule.getConditions()) {
            if (c.getFieldKey() == null) continue;
            Object raw = data.get(c.getFieldKey());
            String v = raw == null ? "" : String.valueOf(raw).trim();
            String want = c.getValue() == null ? "" : c.getValue().trim();
            String op = c.getOp() == null ? "EQUALS" : c.getOp();
            boolean ok = switch (op) {
                case "BLANK" -> v.isEmpty();
                case "NOT_BLANK" -> !v.isEmpty();
                case "NOT_EQUALS" -> !v.equalsIgnoreCase(want);
                case "CONTAINS" -> v.toLowerCase().contains(want.toLowerCase());
                case "GT" -> num(v) != null && num(want) != null && num(v) > num(want);
                case "LT" -> num(v) != null && num(want) != null && num(v) < num(want);
                default -> v.equalsIgnoreCase(want);
            };
            if (!ok) return false;
        }
        return true;
    }

    private LocalDateTime lastCustomerAt(SalesPlaybook pb, RecordDocument record) {
        LocalDateTime best = record.getCreatedAt() == null ? LocalDateTime.now().minusYears(1) : record.getCreatedAt();
        Optional<ChatSession> session = bot.latestSessionFor(record.getId(), pb.getOwnerUserId());
        if (session.isPresent() && session.get().getLastCustomerAt() != null && session.get().getLastCustomerAt().isAfter(best)) {
            best = session.get().getLastCustomerAt();
        }
        var conv = waConversations.findFirstByOwnerUserIdAndRecordIdOrderByIdDesc(pb.getOwnerUserId(), record.getId());
        if (conv.isPresent() && conv.get().getLastInboundAt() != null) {
            LocalDateTime in = LocalDateTime.ofInstant(conv.get().getLastInboundAt(), ZoneId.systemDefault());
            if (in.isAfter(best)) best = in;
        }
        return best;
    }

    private boolean humanDriving(SalesPlaybook pb, RecordDocument record) {
        return bot.latestSessionFor(record.getId(), pb.getOwnerUserId())
                .map(s -> ChatSession.STATUS_HUMAN.equals(s.getStatus()) || ChatSession.STATUS_WAITING.equals(s.getStatus()))
                .orElse(false);
    }

    /* ------------------------------------------------------------- firing */

    private void fire(PlaybookActions.Context ctx, PlaybookRule rule, RecordDocument record, PlaybookRun run, String payload) {
        MessageDeliveryService.Outcome out;
        try {
            out = actions.execute(ctx, rule, record, payload);
        } catch (Exception e) {
            log.warn("Playbook rule '{}' on {} failed: {}", rule.getName(), record.getId(), e.getMessage());
            out = new MessageDeliveryService.Outcome(MessageDeliveryService.NONE, MessageDeliveryService.trim(e.getMessage(), 250));
        }
        LocalDateTime now = LocalDateTime.now();
        PlaybookRun row = run != null ? run
                : PlaybookRun.builder().playbookId(ctx.playbook().getId()).ownerUserId(ctx.playbook().getOwnerUserId())
                    .ruleId(rule.getId()).ruleName(MessageDeliveryService.trim(rule.getName(), 160)).recordId(record.getId())
                    .runCount(0).status(PlaybookRun.ACTIVE).createdAt(now).build();
        row.setRunCount(row.getRunCount() + 1);
        row.setLastRunAt(now);
        row.setLastChannel(out.channel());
        row.setLastOutcome(MessageDeliveryService.trim(out.detail(), 300));
        int max = rule.getMaxRepeats() == null || rule.getMaxRepeats() < 1 ? 1 : rule.getMaxRepeats();
        row.setStatus(row.getRunCount() >= max ? PlaybookRun.DONE : PlaybookRun.ACTIVE);
        runs.save(row);

        if (MESSAGE_CHANNELS.contains(out.channel())) {
            final MessageDeliveryService.Outcome sent = out;
            bot.latestSessionFor(record.getId(), ctx.playbook().getOwnerUserId())
                    .ifPresent(s -> bot.noteAiMessage(s, "[" + rule.getName() + "] " + sent.detail()));
        }
    }

    /* ------------------------------------------------------------ helpers */

    public List<PlaybookRule> rulesOf(SalesPlaybook pb) {
        if (pb.getRulesJson() == null || pb.getRulesJson().isBlank()) return List.of();
        try {
            return mapper.readValue(pb.getRulesJson(), new TypeReference<List<PlaybookRule>>() {});
        } catch (Exception e) {
            log.warn("Playbook {} has unreadable rules: {}", pb.getId(), e.getMessage());
            return List.of();
        }
    }

    public static List<String> qualificationKeys(SalesPlaybook pb) {
        if (pb.getQualificationKeys() == null || pb.getQualificationKeys().isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String k : pb.getQualificationKeys().split(",")) if (!k.trim().isEmpty()) out.add(k.trim());
        return out;
    }

    static boolean inQuietHours(SalesPlaybook pb) {
        if (pb.getQuietStart() == pb.getQuietEnd()) return false;
        ZoneId zone;
        try { zone = ZoneId.of(pb.getTimezone() == null || pb.getTimezone().isBlank() ? "Asia/Kolkata" : pb.getTimezone()); }
        catch (Exception e) { zone = ZoneId.of("Asia/Kolkata"); }
        int hour = ZonedDateTime.ofInstant(Instant.now(), zone).getHour();
        // active window is [quietEnd, quietStart) e.g. 9..20 -> quiet from 20 to 9
        int start = pb.getQuietStart(), end = pb.getQuietEnd();
        return start > end ? (hour >= start || hour < end) : (hour >= start && hour < end);
    }

    static boolean isOutbound(PlaybookRule rule) {
        String a = rule.getAction() == null ? "SEND_MESSAGE" : rule.getAction();
        return a.equals("SEND_MESSAGE") || a.equals("SEND_DOCUMENT");
    }

    static boolean isMessage(PlaybookRule rule) { return isOutbound(rule); }

    private static Double num(String s) {
        try { return s == null || s.isBlank() ? null : Double.parseDouble(s.replaceAll("[^0-9.-]", "")); }
        catch (Exception e) { return null; }
    }
}
