package com.xetax.crm.ai.tools;

import com.xetax.crm.contact.Contact;
import com.xetax.crm.contact.ContactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The assistant could answer about forms, records and stages but knew nothing
 * about the address book, so "how many contacts do I have" was met with "I
 * don't have that information".
 */
class ContactToolsTest {

    private ContactService contacts;
    private ContactTools tools;

    @BeforeEach
    void setUp() {
        contacts = mock(ContactService.class);
        tools = new ContactTools(contacts);

        Contact one = new Contact();
        one.setId(4L);
        one.setName("TEST Rahul");
        one.setPhone("919896458807");
        one.setCompany("TEST Traders");
        when(contacts.list(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(one), PageRequest.of(0, 1), 7));
    }

    @Test
    void itAnswersHowManyContactsThereAre() {
        Map<String, Object> answer = tools.getMyContacts(null, null);

        assertEquals(7L, answer.get("total"));
        assertEquals(1, ((List<?>) answer.get("contacts")).size());
    }

    @Test
    void aContactCarriesTheDetailsSomeoneWouldAskFor() {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>)
                ((List<?>) tools.getMyContacts(0, 20).get("contacts")).get(0);

        assertEquals("TEST Rahul", row.get("name"));
        assertEquals("919896458807", row.get("phone"));
        assertEquals("TEST Traders", row.get("company"));
    }

    @Test
    void aSearchPassesTheWordsThroughToTheAddressBook() {
        tools.searchMyContacts("Rahul", null, null);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(contacts).list(query.capture(), anyInt(), anyInt());
        assertEquals("Rahul", query.getValue());
    }

    @Test
    void anEmptySearchAsksForWordsInsteadOfListingEverything() {
        Map<String, Object> answer = tools.searchMyContacts("  ", null, null);

        assertTrue(String.valueOf(answer.get("error")).contains("searchText"));
        verify(contacts, never()).list(any(), anyInt(), anyInt());
    }

    @Test
    void oneCallCannotPullTheWholeAddressBook() {
        tools.getMyContacts(0, 5000);

        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(contacts).list(any(), anyInt(), size.capture());
        assertEquals(50, size.getValue());
    }
}
