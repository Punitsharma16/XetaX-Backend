package com.xetax.crm.integration;

import com.xetax.crm.ai.rag.KnowledgeIndexer;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.repository.FormFieldRepo;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.service.OwnershipGuard;
import com.xetax.crm.integration.dto.IntegrationResponse;
import com.xetax.crm.integration.dto.MappingItemRequest;
import com.xetax.crm.integration.dto.SaveMappingRequest;
import com.xetax.crm.integration.entity.Integration;
import com.xetax.crm.integration.entity.IntegrationFieldMapping;
import com.xetax.crm.integration.enums.IntegrationStatus;
import com.xetax.crm.integration.enums.IntegrationType;
import com.xetax.crm.integration.mapper.IntegrationMapper;
import com.xetax.crm.integration.repository.IntegrationFieldMappingRepository;
import com.xetax.crm.integration.repository.IntegrationRepository;
import com.xetax.crm.integration.service.IntegrationMappingServiceImpl;
import com.xetax.crm.integration.service.IntegrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IntegrationStatus declared DISABLED from the start, but nothing in the
 * codebase ever assigned it. The only way to stop an integration taking
 * payloads was to delete it — which also threw away its URL, its API key and
 * every field mapping on it.
 */
class IntegrationSwitchTest {

    private IntegrationRepository integrationRepository;
    private IntegrationFieldMappingRepository mappingRepository;
    private FormFieldRepo formFieldRepo;
    private IntegrationServiceImpl service;
    private IntegrationMappingServiceImpl mappingService;

    private Integration integration;

    @BeforeEach
    void setUp() {
        integrationRepository = mock(IntegrationRepository.class);
        mappingRepository = mock(IntegrationFieldMappingRepository.class);
        formFieldRepo = mock(FormFieldRepo.class);
        IntegrationMapper mapper = mock(IntegrationMapper.class);
        OwnershipGuard ownershipGuard = mock(OwnershipGuard.class);
        KnowledgeIndexer knowledgeIndexer = mock(KnowledgeIndexer.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);

        FormEntity form = FormEntity.builder().name("Leads").slug("leads").build();
        form.setId(5L);

        integration = Integration.builder()
                .name("Website form")
                .integrationKey("key-1")
                .apiKey("secret-1")
                .form(form)
                .type(IntegrationType.GENERIC_WEBHOOK)
                .status(IntegrationStatus.ACTIVE)
                .build();
        integration.setId(1L);

        when(integrationRepository.findById(1L)).thenReturn(Optional.of(integration));
        when(integrationRepository.save(any(Integration.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(mapper.toResponse(any(Integration.class)))
                .thenAnswer(call -> {
                    Integration source = call.getArgument(0);
                    IntegrationResponse response = new IntegrationResponse();
                    response.setId(source.getId());
                    response.setStatus(source.getStatus());
                    return response;
                });

        service = new IntegrationServiceImpl(
                mock(FormRepo.class), integrationRepository, mapper,
                mappingRepository, knowledgeIndexer, currentUserProvider, ownershipGuard);

        mappingService = new IntegrationMappingServiceImpl(
                integrationRepository, mappingRepository, formFieldRepo,
                knowledgeIndexer, currentUserProvider, ownershipGuard);
    }

    private void hasMappings(boolean present) {
        when(mappingRepository.findByIntegrationId(anyLong()))
                .thenReturn(present
                        ? List.of(IntegrationFieldMapping.builder()
                                .sourceField("mobile")
                                .formField(FormField.builder()
                                        .fieldKey("phone").label("Phone").build())
                                .build())
                        : List.of());
    }

    // -------------------------------------------------------- the off switch

    @Test
    void anActiveIntegrationCanBeTurnedOff() {
        hasMappings(true);

        IntegrationResponse response = service.setStatus(1L, IntegrationStatus.DISABLED);

        assertEquals(IntegrationStatus.DISABLED, integration.getStatus());
        assertEquals(IntegrationStatus.DISABLED, response.getStatus());
    }

    @Test
    void aDisabledIntegrationCanBeTurnedBackOn() {
        hasMappings(true);
        integration.setStatus(IntegrationStatus.DISABLED);

        service.setStatus(1L, IntegrationStatus.ACTIVE);

        assertEquals(IntegrationStatus.ACTIVE, integration.getStatus());
    }

    @Test
    void turningItOffKeepsTheUrlTheKeyAndTheMappings() {
        hasMappings(true);

        service.setStatus(1L, IntegrationStatus.DISABLED);

        assertEquals("key-1", integration.getIntegrationKey(),
                "disabling must not be a disguised delete");
        assertEquals("secret-1", integration.getApiKey());
        assertTrue(mappingRepository.findByIntegrationId(1L).size() > 0);
    }

    // ------------------------------------------------------------ the rules

    @Test
    void anIntegrationWithNoMappingCannotBeEnabled() {
        hasMappings(false);
        integration.setStatus(IntegrationStatus.PENDING);

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> service.setStatus(1L, IntegrationStatus.ACTIVE));

        assertTrue(thrown.getMessage().toLowerCase().contains("mapping"));
        assertEquals(IntegrationStatus.PENDING, integration.getStatus());
    }

    @Test
    void pendingIsNotSomethingYouCanSwitchTo() {
        hasMappings(true);

        assertThrows(BadRequestException.class,
                () -> service.setStatus(1L, IntegrationStatus.PENDING));

        assertEquals(IntegrationStatus.ACTIVE, integration.getStatus());
    }

    // ------------------------------------------- interaction with mappings

    @Test
    void savingAMappingStillActivatesAPendingIntegration() {
        integration.setStatus(IntegrationStatus.PENDING);
        saveOneMapping();

        assertEquals(IntegrationStatus.ACTIVE, integration.getStatus(),
                "this is how an integration has always gone live");
    }

    @Test
    void savingAMappingDoesNotQuietlyReEnableADisabledIntegration() {
        integration.setStatus(IntegrationStatus.DISABLED);
        saveOneMapping();

        assertEquals(IntegrationStatus.DISABLED, integration.getStatus(),
                "editing configuration is not a decision to start taking payloads again");
    }

    private void saveOneMapping() {
        FormField field = FormField.builder()
                .fieldKey("phone").label("Phone").build();
        field.setId(9L);
        when(formFieldRepo.findByFormIdOrderByDisplayOrder(5L)).thenReturn(List.of(field));
        when(mappingRepository.findByIntegrationId(anyLong())).thenReturn(List.of());

        MappingItemRequest item = new MappingItemRequest();
        item.setSourceField("Mobile");
        item.setFormFieldId(9L);
        SaveMappingRequest request = new SaveMappingRequest();
        request.setMappings(List.of(item));

        mappingService.saveMappings(1L, request);
    }
}
