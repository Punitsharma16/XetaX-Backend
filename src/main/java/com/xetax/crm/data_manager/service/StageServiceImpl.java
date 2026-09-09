package com.xetax.crm.data_manager.service;

import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.dto.StageRequest;
import com.xetax.crm.data_manager.dto.StageResponse;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.mappers.StageMapper;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.StageRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StageServiceImpl implements StageService {

    static final String KNOWLEDGE_MODULE = "stage";

    @Autowired
    StageRepo stageRepo;

    @Autowired
    FormRepo formRepo;

    @Autowired
    KnowledgeIndexer knowledgeIndexer;

    @Autowired
    CurrentUserProvider currentUserProvider;

    @Autowired
    OwnershipGuard ownershipGuard;

    @Autowired
    FormMetaCache formMetaCache;

    private final StageMapper mapper;

    public StageServiceImpl(StageMapper mapper) {
        this.mapper = mapper;
    }

    /* Semantic-index text for a stage — name, code, order, default/final
       flags, status, and the parent form. Saved data only. */
    private String stageKnowledge(FormStage stage) {
        String formName = formRepo.findById(stage.getFormId())
                .map(FormEntity::getName)
                .orElse("unknown");
        StringBuilder sb = new StringBuilder();
        sb.append("CRM Stage \"").append(stage.getName())
                .append("\" (code: ").append(stage.getCode())
                .append(") on form \"").append(formName).append("\".");
        sb.append(" Order/sequence: ").append(stage.getSequence()).append(".");
        sb.append(" Default stage: ").append(Boolean.TRUE.equals(stage.getIsDefault()) ? "yes" : "no").append(".");
        sb.append(" Final stage: ").append(Boolean.TRUE.equals(stage.getIsFinal()) ? "yes" : "no").append(".");
        if (stage.getStatus() != null) {
            sb.append(" Status: ").append(stage.getStatus()).append(".");
        }
        return sb.toString();
    }

    @Override
    public StageResponse create(Long formId, StageRequest request) {

        ownershipGuard.requireOwnedForm(formId);

        /*
         * The condition was inverted: it threw when the code did NOT exist, so
         * every new stage was rejected (and a real duplicate would have slipped
         * through). Type changed to BadRequestException to match the duplicate
         * checks below — a clash is a 400, not a 404.
         */
        if (stageRepo.existsByFormIdAndCode(formId, request.getCode())) {
            throw new BadRequestException("Stage Code Already Exists");
        }

        if (stageRepo.existsByFormIdAndSequence(formId, request.getSequence())) {
            throw new BadRequestException("Stage sequence already exists");
        }

        if (Boolean.TRUE.equals(request.getIsDefault())
                && stageRepo.existsByFormIdAndIsDefaultTrue(formId)) {
            throw new BadRequestException("Default stage already exists");
        }

        FormStage stage = mapper.toEntity(request);
        stage.setFormId(formId);

        FormStage savedStage = stageRepo.save(stage);
        formMetaCache.evictStages(formId);
        knowledgeIndexer.indexEntity(KNOWLEDGE_MODULE, savedStage.getId(), stageKnowledge(savedStage),
                currentUserProvider.currentDataOwnerIdOrNull());
        return mapper.toResponse(savedStage);
    }

    @Override
    public List<StageResponse> getAll(Long formId) {
        ownershipGuard.requireOwnedForm(formId);
        return mapper.toResponse(formMetaCache.getStages(formId));
    }

    @Override
    public StageResponse update(Long id, StageRequest request) {
        FormStage stage = stageRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Stage Not Found"));
        ownershipGuard.requireOwnedForm(stage.getFormId());

        if (stageRepo.existsByFormIdAndCodeAndIdNot(stage.getFormId(), request.getCode(), stage.getId())) {
            throw new BadRequestException("Stage code already exists");
        }

        if (!stage.getSequence().equals(request.getSequence()) && stageRepo.existsByFormIdAndSequence(stage.getFormId(),
                request.getSequence())) {
            throw new BadRequestException("Stage sequence already exists");
        }

        if (Boolean.TRUE.equals(request.getIsDefault()) && !Boolean.TRUE.equals(stage.getIsDefault())
                && stageRepo.existsByFormIdAndIsDefaultTrue(stage.getFormId())) {

            throw new BadRequestException("Default stage already exists");
        }

        mapper.updateEntity(request, stage);
        FormStage updated = stageRepo.save(stage);
        formMetaCache.evictStages(stage.getFormId());
        knowledgeIndexer.reindexEntity(KNOWLEDGE_MODULE, updated.getId(), stageKnowledge(updated),
                currentUserProvider.currentDataOwnerIdOrNull());
        return mapper.toResponse(updated);
    }

    @Override
    public void delete(Long id) {
        FormStage stage = stageRepo.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Stage not found"));
        ownershipGuard.requireOwnedForm(stage.getFormId());

        if (Boolean.TRUE.equals(stage.getIsDefault())) {
            throw new BadRequestException("Default stage cannot be deleted");
        }

        stageRepo.delete(stage);
        formMetaCache.evictStages(stage.getFormId());
        knowledgeIndexer.deleteEntity(KNOWLEDGE_MODULE, id);

    }
}
