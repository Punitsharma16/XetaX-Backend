package com.xetax.crm.integration.service;

import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.repository.FormFieldRepo;
import com.xetax.crm.data_manager.service.OwnershipGuard;
import com.xetax.crm.integration.dto.MappingItemRequest;
import com.xetax.crm.integration.dto.MappingItemResponse;
import com.xetax.crm.integration.dto.SaveMappingRequest;
import com.xetax.crm.integration.entity.Integration;
import com.xetax.crm.integration.entity.IntegrationFieldMapping;
import com.xetax.crm.integration.enums.IntegrationStatus;
import com.xetax.crm.integration.repository.IntegrationFieldMappingRepository;
import com.xetax.crm.integration.repository.IntegrationRepository;
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
public class IntegrationMappingServiceImpl
        implements IntegrationMappingService {

    private final IntegrationRepository integrationRepository;

    private final IntegrationFieldMappingRepository mappingRepository;

    private final FormFieldRepo formFieldRepo;

    private final KnowledgeIndexer knowledgeIndexer;

    private final CurrentUserProvider currentUserProvider;

    private final OwnershipGuard ownershipGuard;

    @Override
    public void saveMappings(Long integrationId, SaveMappingRequest request) {

        Integration integration = integrationRepository.findById(integrationId)
                .orElseThrow(() -> new ResourceNotFoundException("Integration not found"));
        ownershipGuard.assertOwned(integration.getForm());

        mappingRepository.deleteByIntegrationId(integrationId);

        List<FormField> formFields =
                formFieldRepo.findByFormIdOrderByDisplayOrder(
                        integration.getForm().getId()
                );

        Map<Long, FormField> fieldMap = formFields.stream()
                .collect(Collectors.toMap(
                        FormField::getId,
                        Function.identity()
                ));
        List<IntegrationFieldMapping> mappings = new ArrayList<>();

        for (MappingItemRequest item : request.getMappings()) {

            FormField formField = fieldMap.get(item.getFormFieldId());

            if (formField == null) {
                throw new ResourceNotFoundException(
                        "Invalid form field : " + item.getFormFieldId()
                );
            }

            mappings.add(
                    IntegrationFieldMapping.builder()
                            .integration(integration)
                            .sourceField(
                                    item.getSourceField()
                                            .trim()
                                            .toLowerCase()
                            )
                            .formField(formField)
                            .build()
            );
        }
        mappingRepository.saveAll(mappings);
        integration.setStatus(IntegrationStatus.ACTIVE);
        integrationRepository.save(integration);

        // Mappings are part of the webhook configuration (and status just
        // flipped), so the integration's knowledge is refreshed here too.
        knowledgeIndexer.reindexEntity(IntegrationKnowledge.MODULE, integration.getId(),
                IntegrationKnowledge.content(integration, mappings),
                currentUserProvider.currentDataOwnerIdOrNull());

    }

    @Override
    public List<MappingItemResponse> getMappings(Long integrationId) {

        Integration owned = integrationRepository.findById(integrationId)
                .orElseThrow(() -> new ResourceNotFoundException("Integration not found"));
        ownershipGuard.assertOwned(owned.getForm());

        return mappingRepository.findByIntegrationId(integrationId)
                .stream()
                .map(mapping -> MappingItemResponse.builder()
                        .id(mapping.getId())
                        .sourceField(mapping.getSourceField())
                        .formFieldId(mapping.getFormField().getId())
                        .fieldKey(mapping.getFormField().getFieldKey())
                        .fieldLabel(mapping.getFormField().getLabel())
                        .build())
                .toList();
    }
}
