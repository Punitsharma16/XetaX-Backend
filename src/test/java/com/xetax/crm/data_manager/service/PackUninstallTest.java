package com.xetax.crm.data_manager.service;

import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.template.repository.PackInstallRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A vertical pack lives in the form it created, and its own page — the online
 * menu, the booking diary — is in the sidebar because that pack is installed.
 * Deleting the form left the install record behind, so the sidebar kept
 * offering a page whose form was gone and the gallery still called the pack
 * installed.
 */
class PackUninstallTest {

    @Test
    void deletingAPacksFormUninstallsThePack() {
        FormServiceImpl forms = new FormServiceImpl(mock(com.xetax.crm.data_manager.mappers.FormMapper.class));
        forms.formRepo = mock(com.xetax.crm.data_manager.repository.FormRepo.class);
        forms.automationRepository = mock(com.xetax.crm.automation.repository.AutomationRepository.class);
        forms.integrationRepository = mock(com.xetax.crm.integration.repository.IntegrationRepository.class);
        forms.stageRepo = mock(com.xetax.crm.data_manager.repository.StageRepo.class);
        forms.formFieldRepo = mock(com.xetax.crm.data_manager.repository.FormFieldRepo.class);
        forms.packInstallRepository = mock(PackInstallRepository.class);
        forms.ownershipGuard = mock(OwnershipGuard.class);
        forms.formOwnerCache = mock(FormOwnerCache.class);
        forms.formMetaCache = mock(FormMetaCache.class);
        forms.knowledgeIndexer = mock(com.xetax.crm.ai.rag.KnowledgeIndexer.class);

        FormEntity form = new FormEntity();
        form.setId(12L);
        when(forms.formRepo.findById(12L)).thenReturn(Optional.of(form));
        when(forms.automationRepository.findByFormId(anyLong())).thenReturn(List.of());
        when(forms.integrationRepository.findByFormId(anyLong())).thenReturn(List.of());
        when(forms.stageRepo.findByFormIdOrderBySequence(anyLong())).thenReturn(List.of());
        when(forms.formFieldRepo.findByFormIdOrderByDisplayOrder(anyLong())).thenReturn(List.of());

        forms.delete(12L);

        verify(forms.packInstallRepository).deleteByFormId(12L);
        verify(forms.formRepo).delete(form);
    }
}
