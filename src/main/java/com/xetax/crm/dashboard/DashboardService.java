package com.xetax.crm.dashboard;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.automation.entity.Automation;
import com.xetax.crm.automation.repository.AutomationRepository;
import com.xetax.crm.agent.repository.AiAgentRepository;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.repository.StageRepo;
import com.xetax.crm.meeting.entity.Meeting;
import com.xetax.crm.meeting.repository.MeetingRepository;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.team.repository.OrgMemberRoleRepository;
import com.xetax.crm.team.repository.OrgRoleRepository;
import com.xetax.crm.team.service.PermissionService;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.enums.MessageDirection;
import com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-round-trip workspace summary for the dashboard. Everything is scoped to
 * the data owner; record counts additionally respect records.view.own (a
 * member with own-only access sees THEIR numbers, not the whole org's).
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CurrentUserProvider currentUserProvider;
    private final PermissionService permissionService;
    private final FormRepo formRepo;
    private final com.xetax.crm.contact.ContactRepository contactRepository;
    private final StageRepo stageRepo;
    private final RecordRepo recordRepo;
    private final AutomationRepository automationRepository;
    private final OrgMemberRoleRepository memberRepo;
    private final OrgRoleRepository roleRepo;
    private final MeetingRepository meetingRepository;
    private final AiAgentRepository agentRepository;
    private final WhatsAppConfigRepository whatsAppConfigRepository;
    private final WhatsAppMessageRepository whatsAppMessageRepository;
    private final OrgSmtpService orgSmtpService;

    public Map<String, Object> summary() {
        UUID ownerId = currentUserProvider.currentDataOwnerIdOrNull();
        String owner = ownerId == null ? "" : ownerId.toString();

        List<FormEntity> forms = formRepo.findByOwnerUserId(owner);
        List<Long> formIds = forms.stream().map(FormEntity::getId).toList();

        // records.view => org-wide numbers; records.view.own => only mine;
        // neither => the records block is hidden entirely on the frontend.
        boolean seeAll = permissionService.has("records.view");
        boolean seeOwn = seeAll || permissionService.has("records.view.own");
        UUID meId = currentUserProvider.currentUserIdOrNull();
        String mine = (seeAll || meId == null) ? null : meId.toString();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordsVisible", seeOwn);
        out.put("ownOnly", seeOwn && !seeAll);

        // ------------------------------------------------------------ counts
        long totalRecords = 0;
        Map<Long, Long> recordsPerForm = new HashMap<>();
        if (seeOwn) {
            for (Long formId : formIds) {
                long c = mine == null
                        ? recordRepo.countByFormId(formId)
                        : recordRepo.countByFormIdAndAssignedTo(formId, mine);
                recordsPerForm.put(formId, c);
                totalRecords += c;
            }
        }
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        long recordsThisWeek = (!seeOwn || formIds.isEmpty()) ? 0 : (mine == null
                ? recordRepo.countByFormIdInAndCreatedAtGreaterThanEqual(formIds, weekAgo)
                : recordRepo.countByFormIdInAndAssignedToAndCreatedAtGreaterThanEqual(formIds, mine, weekAgo));

        List<Automation> automations = formIds.isEmpty()
                ? List.of() : automationRepository.findByFormIdIn(formIds);
        long activeAutomations = automations.stream().filter(a -> Boolean.TRUE.equals(a.getActive())).count();

        out.put("forms", forms.size());
        out.put("records", totalRecords);
        out.put("recordsThisWeek", recordsThisWeek);
        out.put("automations", Map.of("total", automations.size(), "active", activeAutomations));
        out.put("team", Map.of(
                "members", memberRepo.findByOwnerUserId(owner).size(),
                "roles", roleRepo.findByOwnerUserIdOrderByIdAsc(owner).size()));
        out.put("meetingsUpcoming",
                meetingRepository.countByOwnerUserIdAndStatusIn(owner, List.of("SCHEDULED", "LIVE")));
        out.put("agents", agentRepository.findByOwnerUserIdOrderByIdDesc(owner).size());
        out.put("contacts", contactRepository.countByOwnerUserId(owner));

        // ------------------------------------------------------ integrations
        boolean waConnected = whatsAppConfigRepository.findByOwnerUserId(owner).stream()
                .map(WhatsAppConfig::getStatus)
                .anyMatch(s -> s == WhatsAppConnectionStatus.CONNECTED);
        long waSent7d = waConnected ? whatsAppMessageRepository
                .countByOwnerUserIdAndDirectionAndCreatedAtGreaterThanEqual(owner, MessageDirection.OUTBOUND, weekAgo) : 0;
        out.put("whatsapp", Map.of("connected", waConnected, "sent7d", waSent7d));
        out.put("emailConfigured", orgSmtpService.isConfiguredFor(owner));

        // ------------------------------------------- pipelines (top 4 forms)
        List<Map<String, Object>> pipelines = new ArrayList<>();
        if (seeOwn) {
            forms.stream()
                    .sorted((a, b) -> Long.compare(
                            recordsPerForm.getOrDefault(b.getId(), 0L),
                            recordsPerForm.getOrDefault(a.getId(), 0L)))
                    .limit(4)
                    .forEach(form -> {
                        List<Map<String, Object>> stageRows = new ArrayList<>();
                        for (FormStage stage : stageRepo.findByFormIdOrderBySequence(form.getId())) {
                            long c = mine == null
                                    ? recordRepo.countByFormIdAndStageId(form.getId(), stage.getId())
                                    : recordRepo.countByFormIdAndStageIdAndAssignedTo(form.getId(), stage.getId(), mine);
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", stage.getName());
                            row.put("color", stage.getColor());
                            row.put("isFinal", Boolean.TRUE.equals(stage.getIsFinal()));
                            row.put("count", c);
                            stageRows.add(row);
                        }
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("formId", form.getId());
                        p.put("formName", form.getName());
                        p.put("icon", form.getIcon());
                        p.put("color", form.getColor());
                        p.put("records", recordsPerForm.getOrDefault(form.getId(), 0L));
                        p.put("stages", stageRows);
                        pipelines.add(p);
                    });
        }
        out.put("pipelines", pipelines);

        // -------------------------------------------------- recent activity
        List<Map<String, Object>> recent = new ArrayList<>();
        if (seeOwn && !formIds.isEmpty()) {
            Map<Long, String> formNames = new HashMap<>();
            forms.forEach(f -> formNames.put(f.getId(), f.getName()));
            Map<Long, String> stageNames = new HashMap<>();
            List<RecordDocument> latest = (mine == null
                    ? recordRepo.findByFormIdInOrderByCreatedAtDesc(formIds, PageRequest.of(0, 6))
                    : recordRepo.findByFormIdInAndAssignedToOrderByCreatedAtDesc(formIds, mine, PageRequest.of(0, 6)))
                    .getContent();
            for (RecordDocument record : latest) {
                String stageName = stageNames.computeIfAbsent(record.getStageId(), id ->
                        id == null ? null : stageRepo.findById(id).map(FormStage::getName).orElse(null));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("recordId", record.getId());
                row.put("formId", record.getFormId());
                row.put("formName", formNames.get(record.getFormId()));
                row.put("title", displayTitle(record));
                row.put("stageName", stageName);
                row.put("createdAt", record.getCreatedAt());
                recent.add(row);
            }
        }
        out.put("recent", recent);

        // ------------------------------------------ upcoming meetings (list)
        List<Map<String, Object>> meetings = new ArrayList<>();
        for (Meeting meeting : meetingRepository
                .findTop3ByOwnerUserIdAndStatusInOrderByScheduledAtAsc(owner, List.of("SCHEDULED", "LIVE"))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", meeting.getId());
            row.put("title", meeting.getTitle());
            row.put("status", meeting.getStatus());
            row.put("scheduledAt", meeting.getScheduledAt());
            meetings.add(row);
        }
        out.put("meetings", meetings);

        return out;
    }

    /** Best-effort human label for a record — first name-ish field, else any text. */
    private String displayTitle(RecordDocument record) {
        Map<String, Object> data = record.getData();
        if (data == null || data.isEmpty()) return null;
        for (String key : List.of("name", "full_name", "patient_name", "customer_name", "title", "item_name", "subject")) {
            Object v = data.get(key);
            if (v instanceof String s && !s.isBlank()) return s;
        }
        return data.values().stream()
                .filter(v -> v instanceof String s && !s.isBlank())
                .map(Object::toString)
                .findFirst().orElse(null);
    }
}
