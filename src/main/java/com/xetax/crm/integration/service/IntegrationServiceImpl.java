package com.xetax.crm.integration.service;


import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.service.OwnershipGuard;
import com.xetax.crm.integration.dto.IntegrationRequest;
import com.xetax.crm.integration.dto.IntegrationResponse;
import com.xetax.crm.integration.entity.Integration;
import com.xetax.crm.integration.enums.IntegrationStatus;
import com.xetax.crm.integration.mapper.IntegrationMapper;
import com.xetax.crm.integration.repository.IntegrationFieldMappingRepository;
import com.xetax.crm.integration.repository.IntegrationRepository;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@AllArgsConstructor
@Transactional
public class IntegrationServiceImpl implements IntegrationService {

    private final FormRepo formRepo;

    private final IntegrationRepository integrationRepository;

    private final IntegrationMapper mapper;

    private final IntegrationFieldMappingRepository mappingRepository;

    private final KnowledgeIndexer knowledgeIndexer;

    private final CurrentUserProvider currentUserProvider;

    private final OwnershipGuard ownershipGuard;

    @Override
    public IntegrationResponse create(IntegrationRequest request) {

        FormEntity form = formRepo.findById(request.getFormId()).orElseThrow(() ->
                new ResourceNotFoundException("Form not found"));
        ownershipGuard.assertOwned(form);

        Integration integration = Integration.builder()
                .name(request.getName())
                .description(request.getDescription())
                .type(request.getType())
                .status(IntegrationStatus.PENDING)
                .integrationKey(UUID.randomUUID().toString())
                .apiKey(UUID.randomUUID().toString())
                .form(form)
                .build();

        integration = integrationRepository.save(integration);
        knowledgeIndexer.indexEntity(IntegrationKnowledge.MODULE, integration.getId(),
                IntegrationKnowledge.content(integration, List.of()),
                currentUserProvider.currentDataOwnerIdOrNull());
        IntegrationResponse response = mapper.toResponse(integration);

        response.setEndpoint(
                buildEndpoint(integration.getIntegrationKey())
        );
        return response;
    }

    @Override
    public IntegrationResponse update(Long id, IntegrationRequest request) {
        Integration integration = integrationRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Integration not found"));
        ownershipGuard.assertOwned(integration.getForm());

        FormEntity form = formRepo.findById(request.getFormId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Form not found"));
        ownershipGuard.assertOwned(form);

        integration.setName(request.getName());
        integration.setDescription(request.getDescription());
        integration.setType(request.getType());
        integration.setForm(form);

        Integration updatedIntegration = integrationRepository.save(integration);
        knowledgeIndexer.reindexEntity(IntegrationKnowledge.MODULE, updatedIntegration.getId(),
                IntegrationKnowledge.content(updatedIntegration,
                        mappingRepository.findByIntegrationId(updatedIntegration.getId())),
                currentUserProvider.currentDataOwnerIdOrNull());

        return buildResponse(integration);
    }

    @Override
    public IntegrationResponse getById(Long id) {

        Integration integration = integrationRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Integration not found"));
        ownershipGuard.assertOwned(integration.getForm());

        return buildResponse(integration);
    }

    @Override
    public List<IntegrationResponse> getAll() {

        return integrationRepository.findAll()
                .stream()
                .filter(integration -> ownershipGuard.owns(integration.getForm()))
                .map(integration -> {
                    IntegrationResponse response = mapper.toResponse(integration);
                    response.setEndpoint(buildEndpoint(integration.getIntegrationKey())
                    );
                    return response;

                }).toList();
    }

    @Override
    public void delete(Long id) {

        Integration integration = integrationRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Integration not found"));
        ownershipGuard.assertOwned(integration.getForm());

        integrationRepository.delete(integration);
        knowledgeIndexer.deleteEntity(IntegrationKnowledge.MODULE, id);

    }

    private IntegrationResponse buildResponse(Integration integration) {
        IntegrationResponse response = mapper.toResponse(integration);
        response.setEndpoint(buildEndpoint(integration.getIntegrationKey()));

        return response;
    }

    private String buildEndpoint(String integrationKey) {
        return "/api/public/integrations/" + integrationKey;
    }
}
