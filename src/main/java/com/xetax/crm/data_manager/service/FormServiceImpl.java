package com.xetax.crm.data_manager.service;

import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.dto.FormRequest;
import com.xetax.crm.data_manager.dto.FormResponse;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.mappers.FormMapper;
import com.xetax.crm.data_manager.repository.FormRepo;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class FormServiceImpl implements FormService{

    static final String KNOWLEDGE_MODULE = "form";

    @Autowired
    FormRepo formRepo;

    @Autowired
    com.xetax.crm.billing.PlanLimitService planLimitService;

    @Autowired
    KnowledgeIndexer knowledgeIndexer;

    @Autowired
    CurrentUserProvider currentUserProvider;

    @Autowired
    OwnershipGuard ownershipGuard;

    @Autowired
    FormMetaCache formMetaCache;

    @Autowired
    FormOwnerCache formOwnerCache;

    @Autowired
    com.xetax.crm.automation.repository.AutomationRepository automationRepository;

    @Autowired
    com.xetax.crm.automation.repository.AutomationActionRepository automationActionRepository;

    @Autowired
    com.xetax.crm.automation.repository.AutomationConditionRepository automationConditionRepository;

    @Autowired
    com.xetax.crm.data_manager.repository.FormFieldRepo formFieldRepo;

    @Autowired
    com.xetax.crm.data_manager.repository.StageRepo stageRepo;

    private final FormMapper formMapper;

    public FormServiceImpl(FormMapper formMapper) {
        this.formMapper = formMapper;
    }

    /* Plain-text summary of the form for the semantic index — no business
       logic, just the saved entity's own data. */
    private String formKnowledge(FormEntity form) {
        StringBuilder sb = new StringBuilder();
        sb.append("CRM Form \"").append(form.getName())
                .append("\" (slug: ").append(form.getSlug()).append(").");
        if (form.getDescription() != null && !form.getDescription().isBlank()) {
            sb.append(" Description: ").append(form.getDescription()).append(".");
        }
        return sb.toString();
    }

    @Override
    public FormResponse create(@Valid FormRequest request) {
        if (formRepo.existsBySlug(request.getSlug())){
            throw new BadRequestException("Slug Already Exists");
        }

        FormEntity form = formMapper.toEntity(request);
        java.util.UUID ownerId = currentUserProvider.currentDataOwnerIdOrNull();
        if (ownerId != null) {
            planLimitService.assertCanCreateForm(ownerId.toString());
            form.setOwnerUserId(ownerId.toString());
        }
        FormEntity saved = formRepo.save(form);
        knowledgeIndexer.indexEntity(KNOWLEDGE_MODULE, saved.getId(), formKnowledge(saved),
                ownerId);
        return formMapper.toResponse(saved);
    }

    @Override
    public List<FormResponse> getAll() {
        /* Only the logged-in user's forms — identity comes from the verified
           JWT in the SecurityContext, never from the client. */
        UUID userId = currentUserProvider.currentDataOwnerIdOrNull();
        if (userId == null) {
            return List.of();
        }
        return formMapper.toResponse(formRepo.findByOwnerUserId(userId.toString()));
    }

    @Override
    public FormResponse getById(Long id) {
        FormEntity form = formRepo.findById(id).orElseThrow(()-> new ResourceNotFoundException("Form Not Found"));
        ownershipGuard.assertOwned(form);
        return formMapper.toResponse(form);
    }

    @Override
    public FormResponse update(Long id, @Valid FormRequest request) {
        FormEntity form = formRepo.findById(id).orElseThrow(()-> new ResourceNotFoundException("Form Not Found"));
        ownershipGuard.assertOwned(form);
        /*
         * Block only a CHANGED slug that another form already owns. The old
         * check was inverted (it rejected every update that kept its own
         * slug, so the edit form could never be saved unchanged).
         */
        if(!form.getSlug().equals(request.getSlug()) && formRepo.existsBySlug(request.getSlug())){
            throw new BadRequestException("Slug is already present");
        }
        formMapper.updateEntity(request , form);
        FormEntity saved = formRepo.save(form);
        knowledgeIndexer.reindexEntity(KNOWLEDGE_MODULE, saved.getId(), formKnowledge(saved),
                currentUserProvider.currentDataOwnerIdOrNull());
        return formMapper.toResponse(saved);

    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void delete(Long id) {

        FormEntity form = formRepo.findById(id).orElseThrow(()-> new ResourceNotFoundException("Form Not Found"));
        ownershipGuard.assertOwned(form);
        // FK order: automation children -> automations -> stages/fields -> form.
        // (Deleting the form first hits the automations.form_id constraint.)
        for (var automation : automationRepository.findByFormId(id)) {
            automationConditionRepository.deleteByAutomationId(automation.getId());
            automationActionRepository.deleteByAutomationId(automation.getId());
            automationRepository.delete(automation);
        }
        stageRepo.deleteAll(stageRepo.findByFormIdOrderBySequence(id));
        formFieldRepo.deleteAll(formFieldRepo.findByFormIdOrderByDisplayOrder(id));
        formRepo.delete(form);
        formOwnerCache.evict(id);
        // The form's cached children go with it — nothing should serve them.
        formMetaCache.evictFields(id);
        formMetaCache.evictStages(id);
        knowledgeIndexer.deleteEntity(KNOWLEDGE_MODULE, id);
    }
}

