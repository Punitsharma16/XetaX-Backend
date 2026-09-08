package com.xetax.crm.automation.service;

import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.automation.dto.AutomationRequest;
import com.xetax.crm.automation.dto.AutomationResponse;
import com.xetax.crm.automation.entity.Automation;
import com.xetax.crm.automation.mapper.AutomationMapper;
import com.xetax.crm.automation.repository.AutomationConditionRepository;
import com.xetax.crm.automation.repository.AutomationRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormFieldRepo;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.StageRepo;
import com.xetax.crm.data_manager.service.OwnershipGuard;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@AllArgsConstructor
public class AutomationServiceImpl implements AutomationService{

    static final String KNOWLEDGE_MODULE = AutomationKnowledge.MODULE;

    private final FormRepo formRepo;

    private final StageRepo stageRepo;

    private final FormFieldRepo formFieldRepo;

    private final AutomationRepository automationRepository;

    private final AutomationConditionRepository conditionRepository;

    private final com.xetax.crm.automation.repository.AutomationActionRepository actionRepository;


    private final com.xetax.crm.document.DocumentFileRepository documentFileRepository;

    private final AutomationMapper mapper;

    private final KnowledgeIndexer knowledgeIndexer;

    private final CurrentUserProvider currentUserProvider;

    private final OwnershipGuard ownershipGuard;

    private final com.xetax.crm.automation.engine.AutomationRuleCache automationRuleCache;

    /* Semantic-index text via the shared AutomationKnowledge builder, with
       the rule's current conditions so the indexed text always reflects the
       full rule. Email message/subject are rule content, not secrets. */
    private String automationKnowledge(Automation automation) {
        String stageName = automation.getTriggerStageId() == null ? null
                : stageRepo.findById(automation.getTriggerStageId())
                        .map(FormStage::getName)
                        .orElse(null);
        return AutomationKnowledge.content(automation,
                conditionRepository.findByAutomationId(automation.getId()),
                stageName);
    }

    @Override
    public AutomationResponse create(AutomationRequest request) {

        FormEntity form = formRepo.findById(request.getFormId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Form not found"));
        ownershipGuard.assertOwned(form);

        Automation automation = Automation.builder()
                .name(request.getName())
                .description(request.getDescription())
                .trigger(request.getTrigger())
                .active(request.getActive() == null ? true : request.getActive())
                .form(form)
                .build();

        applyRuleFields(automation, request, form);

        Automation saved = automationRepository.save(automation);
        automationRuleCache.evict(form.getId());
        knowledgeIndexer.indexEntity(KNOWLEDGE_MODULE, saved.getId(), automationKnowledge(saved),
                currentUserProvider.currentDataOwnerIdOrNull());
        return mapper.toResponse(saved);
    }

    @Override
    public AutomationResponse update(Long id, AutomationRequest request) {

        Automation automation = automationRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Automation not found"));
        ownershipGuard.assertOwned(automation.getForm());
        Long previousFormId = automation.getForm().getId();

        FormEntity form = formRepo.findById(request.getFormId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Form not found"));
        ownershipGuard.assertOwned(form);

        automation.setName(request.getName());
        automation.setDescription(request.getDescription());
        automation.setTrigger(request.getTrigger());
        if (request.getActive() != null) {
            automation.setActive(request.getActive());
        }
        automation.setForm(form);

        applyRuleFields(automation, request, form);

        Automation saved = automationRepository.save(automation);
        automationRuleCache.evict(form.getId());
        if (!previousFormId.equals(form.getId())) {
            // Rule moved to another form — the old form's presence flag is stale.
            automationRuleCache.evict(previousFormId);
        }
        knowledgeIndexer.reindexEntity(KNOWLEDGE_MODULE, saved.getId(), automationKnowledge(saved),
                currentUserProvider.currentDataOwnerIdOrNull());
        return mapper.toResponse(saved);
    }

    /**
     * Copies the single-table rule fields (trigger stage + inline action) onto
     * the entity, validating that referenced stage/field belong to the form.
     */
    private void applyRuleFields(Automation automation, AutomationRequest request, FormEntity form) {

        Long triggerStageId = null;
        if (request.getTriggerStageId() != null) {
            FormStage stage = stageRepo.findById(request.getTriggerStageId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Trigger stage not found"));

            if (!stage.getFormId().equals(form.getId())) {
                throw new BadRequestException("Trigger stage does not belong to this form");
            }
            triggerStageId = stage.getId();
        }
        automation.setTriggerStageId(triggerStageId);

        automation.setTriggerStatusId(null); // record statuses removed

        FormField actionField = null;
        if (request.getActionFieldId() != null) {
            actionField = formFieldRepo.findById(request.getActionFieldId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Action field not found"));

            if (!actionField.getFormId().equals(form.getId())) {
                throw new BadRequestException("Action field does not belong to this form");
            }
        }

        automation.setActionType(request.getActionType());
        automation.setActionField(actionField);
        automation.setActionValue(request.getActionValue());
        automation.setEmailSubject(request.getEmailSubject());
        automation.setEmailMessage(request.getEmailMessage());

        // SEND_DOCUMENT: the document must exist and belong to the form's org.
        Long documentId = null;
        String channel = null;
        if (request.getActionType() == com.xetax.crm.automation.enums.AutomationActionType.SEND_DOCUMENT) {
            if (request.getDocumentId() == null) {
                throw new BadRequestException("Pick the document this automation should send");
            }
            documentFileRepository
                    .findByIdAndOwnerUserId(request.getDocumentId(), form.getOwnerUserId())
                    .orElseThrow(() -> new BadRequestException("Document not found"));
            documentId = request.getDocumentId();
            channel = "EMAIL".equalsIgnoreCase(request.getChannel()) ? "EMAIL" : "WHATSAPP";
            if (request.getActionFieldId() == null) {
                throw new BadRequestException(
                        "Pick the field that holds the recipient's "
                        + ("EMAIL".equals(channel) ? "email" : "phone number"));
            }
        }
        automation.setDocumentId(documentId);
        automation.setChannel(channel);
    }

    @Override
    public AutomationResponse getById(Long id) {

        Automation automation = automationRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Automation not found"));
        ownershipGuard.assertOwned(automation.getForm());

        return mapper.toResponse(automation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutomationResponse> getAll() {
        // Only the current user's rules — same boundary as the AI tools.
        return automationRepository.findAll()
                .stream()
                .filter(a -> ownershipGuard.owns(a.getForm()))
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void delete(Long id) {
        Automation automation = automationRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Automation not found"));
        ownershipGuard.assertOwned(automation.getForm());
        Long ruleFormId = automation.getForm().getId();
        // Children first — condition/action rows FK-reference the automation,
        // so deleting the parent alone blew up with a constraint violation.
        conditionRepository.deleteByAutomationId(id);
        actionRepository.deleteByAutomationId(id);
        automationRepository.delete(automation);
        automationRuleCache.evict(ruleFormId);
        knowledgeIndexer.deleteEntity(KNOWLEDGE_MODULE, id);
    }
}
