package com.xetax.crm.automation.service;

import com.xetax.crm.automation.entity.Automation;
import com.xetax.crm.automation.entity.AutomationCondition;
import com.xetax.crm.automation.entity.AutomationConditionRequest;
import com.xetax.crm.automation.entity.AutomationConditionResponse;
import com.xetax.crm.automation.mapper.AutomationConditionMapper;
import com.xetax.crm.automation.repository.AutomationConditionRepository;
import com.xetax.crm.automation.repository.AutomationRepository;
import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormFieldRepo;
import com.xetax.crm.data_manager.repository.StageRepo;
import com.xetax.crm.data_manager.service.OwnershipGuard;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class AutomationConditionServiceImpl implements AutomationConditionService {

    private final AutomationRepository automationRepository;

    private final AutomationConditionRepository conditionRepository;

    private final FormFieldRepo formFieldRepository;

    private final AutomationConditionMapper conditionMapper;

    private final StageRepo stageRepo;

    private final KnowledgeIndexer knowledgeIndexer;

    private final CurrentUserProvider currentUserProvider;

    private final OwnershipGuard ownershipGuard;

    @Override
    public List<AutomationConditionResponse> saveConditions(Long automationId, List<AutomationConditionRequest> requests) {

        Automation automation = automationRepository.findById(automationId)
                .orElseThrow(() -> new ResourceNotFoundException("Automation not found"));
        ownershipGuard.assertOwned(automation.getForm());

        conditionRepository.deleteByAutomationId(automationId);

        List<FormField> formFields = formFieldRepository.findByFormIdOrderByDisplayOrder(
                automation.getForm().getId());

        Map<Long, FormField> fieldMap = formFields.stream()
                .collect(Collectors.toMap(
                        FormField::getId,
                        Function.identity()
                ));

        List<AutomationCondition> conditions = new ArrayList<>();

        for (AutomationConditionRequest request : requests) {

            FormField formField = fieldMap.get(request.getFormFieldId());
            if (formField == null) {
                throw new ResourceNotFoundException(
                        "Invalid form field : " + request.getFormFieldId()
                );
            }

            conditions.add(
                    AutomationCondition.builder()
                            .automation(automation)
                            .formField(formField)
                            .operator(request.getOperator())
                            .value(request.getValue())
                            .build()
            );
        }

        List<AutomationCondition> saved = conditionRepository.saveAll(conditions);

        /*
         * Conditions are part of the rule's configuration, so the rule's
         * knowledge is refreshed here with the freshly saved set. The UI
         * saves the rule sequentially (update → conditions), which makes
         * this the LAST reindex — the indexed text always ends up with the
         * complete, current rule. KnowledgeIndexer never throws, so a
         * Qdrant/embedding failure cannot roll this transaction back.
         */
        String stageName = automation.getTriggerStageId() == null ? null
                : stageRepo.findById(automation.getTriggerStageId())
                        .map(FormStage::getName)
                        .orElse(null);
        knowledgeIndexer.reindexEntity(AutomationKnowledge.MODULE, automation.getId(),
                AutomationKnowledge.content(automation, saved, stageName),
                currentUserProvider.currentDataOwnerIdOrNull());

        return saved
                .stream()
                .map(conditionMapper::toResponse)
                .toList();
    }

    @Override
    public List<AutomationConditionResponse> getConditions(Long automationId) {

        Automation automation = automationRepository.findById(automationId)
                .orElseThrow(() -> new ResourceNotFoundException("Automation not found"));
        ownershipGuard.assertOwned(automation.getForm());

        return conditionRepository.findByAutomationId(automationId)
                .stream()
                .map(conditionMapper::toResponse)
                .toList();

    }
}
