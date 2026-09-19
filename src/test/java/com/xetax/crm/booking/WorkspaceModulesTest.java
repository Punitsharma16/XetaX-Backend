package com.xetax.crm.booking;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.template.controller.WorkspaceModulesController;
import com.xetax.crm.template.entity.PackInstall;
import com.xetax.crm.template.repository.PackInstallRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A pack's own page belongs to the workspace that installed that pack. A salon
 * has no online menu and a restaurant has no stylist diary; neither should
 * carry the other's page in its sidebar.
 */
class WorkspaceModulesTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private Map<String, Boolean> modulesFor(String... packKeys) {
        PackInstallRepository installs = mock(PackInstallRepository.class);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.currentDataOwnerIdOrNull()).thenReturn(OWNER);
        when(installs.findByOwnerUserIdOrderByInstalledAtDesc(OWNER.toString())).thenReturn(
                java.util.Arrays.stream(packKeys)
                        .map(key -> PackInstall.builder().ownerUserId(OWNER.toString()).packKey(key).formId(1L).build())
                        .toList());
        return new WorkspaceModulesController(installs, users).modules().getData();
    }

    @Test
    void aSalonSeesItsDiaryAndNoMenu() {
        Map<String, Boolean> modules = modulesFor("salon");

        assertTrue(modules.get("booking"));
        assertFalse(modules.get("menu"));
    }

    @Test
    void aRestaurantSeesItsMenuAndNoDiary() {
        Map<String, Boolean> modules = modulesFor("restaurant");

        assertTrue(modules.get("menu"));
        assertFalse(modules.get("booking"));
    }

    @Test
    void aWorkspaceWithNeitherPackSeesNeitherPage() {
        Map<String, Boolean> modules = modulesFor("real_estate");

        assertFalse(modules.get("menu"));
        assertFalse(modules.get("booking"));
    }

    @Test
    void aWorkspaceRunningBothGetsBoth() {
        Map<String, Boolean> modules = modulesFor("salon", "restaurant");

        assertTrue(modules.get("menu"));
        assertTrue(modules.get("booking"));
    }
}
