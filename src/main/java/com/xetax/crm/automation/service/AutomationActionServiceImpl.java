package com.xetax.crm.automation.service;


import com.xetax.crm.automation.dto.AutomationActionRequest;
import com.xetax.crm.automation.dto.AutomationActionResponse;
import com.xetax.crm.automation.entity.Automation;
import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.mapper.AutomationActionMapper;
import com.xetax.crm.automation.repository.AutomationActionRepository;
import com.xetax.crm.automation.repository.AutomationRepository;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.repository.FormFieldRepo;
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
public class AutomationActionServiceImpl implements AutomationActionService{

    private final AutomationActionRepository actionRepository;

    private final AutomationRepository automationRepository;

    private final com.xetax.crm.data_manager.service.OwnershipGuard ownershipGuard;

    private final FormFieldRepo formFieldRepo;

    private final AutomationActionMapper mapper;


    @Override
    public List<AutomationActionResponse> saveActions(Long automationId, List<AutomationActionRequest> requests) {
        Automation automation = automationRepository.findById(automationId).orElseThrow(() ->
                        new ResourceNotFoundException("Automation not found"));
        ownershipGuard.assertOwned(automation.getForm());

        actionRepository.deleteByAutomationId(automationId);

        List<FormField> formFields =
                formFieldRepo.findByFormIdOrderByDisplayOrder(
                        automation.getForm().getId()
                );

        Map<Long, FormField> fieldMap = formFields.stream()
                .collect(Collectors.toMap(
                        FormField::getId,
                        Function.identity()
                ));

        List<AutomationAction> actions = new ArrayList<>();

        for (AutomationActionRequest request : requests) {

            FormField formField = null;
            if (request.getFormFieldId() != null) {
                formField = fieldMap.get(request.getFormFieldId());

                if (formField == null) {
                    throw new ResourceNotFoundException(
                            "Invalid Form Field : " + request.getFormFieldId()
                    );
                }
            }

            actions.add(
                    AutomationAction.builder()
                            .automation(automation)
                            .actionType(request.getActionType())
                            .formField(formField)
                            .value(request.getValue())
                            .emailSubject(request.getEmailSubject())
                            .emailMessage(request.getEmailMessage())
                            .executionOrder(request.getExecutionOrder())
                            .build()
            );
        }

        return actionRepository.saveAll(actions)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public List<AutomationActionResponse> getActions(Long automationId) {
        Automation automation = automationRepository.findById(automationId)
                .orElseThrow(() -> new ResourceNotFoundException("Automation not found"));
        ownershipGuard.assertOwned(automation.getForm());
        return actionRepository
                .findByAutomationIdOrderByExecutionOrderAsc(
                        automationId
                )
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

}
