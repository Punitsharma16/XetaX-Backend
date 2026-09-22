package com.xetax.crm.datamanager;

import com.xetax.crm.data_manager.controller.FormController;
import com.xetax.crm.data_manager.service.FormService;
import com.xetax.crm.team.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Records always belong to a form, so the Records page starts by listing
 * forms. A sales agent carries records.view and not forms.view, and the list
 * answered 403: their own page opened empty and told them to create a form
 * they were not allowed to create.
 */
class FormAccessTest {

    private FormController controllerFor(Set<String> granted) {
        PermissionService permissions = mock(PermissionService.class);
        doAnswer(call -> {
            for (Object key : call.getArguments()[0] instanceof Object[] keys ? keys : call.getArguments()) {
                if (granted.contains(String.valueOf(key))) return null;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role does not allow this");
        }).when(permissions).requireAny(org.mockito.ArgumentMatchers.any(String[].class));

        FormService forms = mock(FormService.class);
        when(forms.getAll()).thenReturn(List.of());
        return new FormController(forms, permissions);
    }

    @Test
    void aMemberWhoMayReadRecordsMayListTheForms() {
        var response = controllerFor(Set.of("records.view")).getAll();

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void aMemberWhoOnlySeesTheirOwnRecordsMayListTheFormsToo() {
        var response = controllerFor(Set.of("records.view.own")).getAll();

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void aMemberWithNeitherIsStillRefused() {
        FormController controller = controllerFor(Set.of("contacts.view"));

        ResponseStatusException refused = assertThrows(ResponseStatusException.class, controller::getAll);

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
    }
}
