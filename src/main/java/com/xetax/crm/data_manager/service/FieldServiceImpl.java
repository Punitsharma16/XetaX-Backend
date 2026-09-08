package com.xetax.crm.data_manager.service;

import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.dto.FieldRequest;
import com.xetax.crm.data_manager.dto.FieldResponse;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.mappers.FormFieldMapper;
import com.xetax.crm.data_manager.repository.FormFieldRepo;
import com.xetax.crm.data_manager.repository.FormRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/*
 * @Service was missing, so no FieldService bean existed and FormFieldController
 * failed to start ("No qualifying bean of type FieldService"). The other
 * *ServiceImpl classes in this package are all annotated the same way.
 */
@Service
public class FieldServiceImpl implements FieldService {
    @Autowired
    FormFieldRepo formFieldRepo;

    @Autowired
    FormRepo formRepo;

    @Autowired
    FormFieldMapper formFieldMapper;

    @Autowired
    KnowledgeIndexer knowledgeIndexer;

    @Autowired
    CurrentUserProvider currentUserProvider;

    @Autowired
    OwnershipGuard ownershipGuard;

    @Autowired
    FormMetaCache formMetaCache;

    static final String KNOWLEDGE_MODULE = "field";

    /* Semantic-index text for a field, keeping the User→Form→Field link by
       naming the parent form. Reads saved data only. */
    private String fieldKnowledge(FormField field) {
        String formName = formRepo.findById(field.getFormId())
                .map(FormEntity::getName)
                .orElse("unknown");
        StringBuilder sb = new StringBuilder();
        sb.append("CRM Field \"").append(field.getLabel())
                .append("\" (key: ").append(field.getFieldKey())
                .append(", type: ").append(field.getFieldType())
                .append(") on form \"").append(formName).append("\".");
        sb.append(" Required: ").append(Boolean.TRUE.equals(field.getRequired()) ? "yes" : "no").append(".");
        if (field.getOptionsJson() != null && !field.getOptionsJson().isBlank()) {
            sb.append(" Options: ").append(field.getOptionsJson()).append(".");
        }
        if (field.getDefaultValue() != null && !field.getDefaultValue().isBlank()) {
            sb.append(" Default value: ").append(field.getDefaultValue()).append(".");
        }
        return sb.toString();
    }

    @Override

    public FieldResponse create(Long formId, FieldRequest request) {
        ownershipGuard.requireOwnedForm(formId);
        if(formFieldRepo.existsByFormIdAndFieldKey(formId, request.getFieldKey())){
            throw  new BadRequestException("Field Key already exists");
        }
        FormField field = formFieldMapper.toEntity(request);
        field.setFormId(formId);
        FormField saved= formFieldRepo.save(field);
        formMetaCache.evictFields(formId);
        knowledgeIndexer.indexEntity(KNOWLEDGE_MODULE, saved.getId(), fieldKnowledge(saved),
                currentUserProvider.currentDataOwnerIdOrNull());
        return formFieldMapper.toResponse(saved);
    }

    @Override
    public List<FieldResponse> getAll(Long formId) {
        ownershipGuard.requireOwnedForm(formId);
        return formFieldMapper.toResponse(formMetaCache.getFields(formId));
    }

    @Override
    public FieldResponse update(Long id, FieldRequest request) {
        FormField field = formFieldRepo.findById(id).orElseThrow(()-> new ResourceNotFoundException("Field Not Found"));
        ownershipGuard.requireOwnedForm(field.getFormId());
        formFieldMapper.updateEntity(request , field);

        FormField saved = formFieldRepo.save(field);
        formMetaCache.evictFields(field.getFormId());
        knowledgeIndexer.reindexEntity(KNOWLEDGE_MODULE, saved.getId(), fieldKnowledge(saved),
                currentUserProvider.currentDataOwnerIdOrNull());
        return formFieldMapper.toResponse(saved);
    }

    @Override
    public void delete(Long id) {
        FormField field = formFieldRepo.findById(id).orElseThrow(()-> new ResourceNotFoundException("Field Not Found"));
        ownershipGuard.requireOwnedForm(field.getFormId());
        formFieldRepo.delete(field);
        formMetaCache.evictFields(field.getFormId());
        knowledgeIndexer.deleteEntity(KNOWLEDGE_MODULE, id);
    }
}
