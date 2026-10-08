package com.xetax.crm.emailcampaign;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.exception.UnauthorizedException;
import com.xetax.crm.emailcampaign.dto.EmailTemplateRequest;
import com.xetax.crm.emailcampaign.dto.EmailTemplateResponse;
import com.xetax.crm.emailcampaign.entity.EmailTemplate;
import com.xetax.crm.emailcampaign.repository.EmailTemplateRepository;
import com.xetax.crm.emailcampaign.service.EmailTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Templates are workspace data: one owner must never read, rename over or
 * delete another's, and a saved template must stay unique by name so the
 * campaign wizard's picker is unambiguous.
 */
class EmailTemplateServiceTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String OWNER_ID = OWNER.toString();

    private EmailTemplateRepository repository;
    private CurrentUserProvider users;
    private EmailTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(EmailTemplateRepository.class);
        users = mock(CurrentUserProvider.class);
        when(users.currentDataOwnerIdOrNull()).thenReturn(OWNER);
        when(repository.save(any(EmailTemplate.class))).thenAnswer(i -> i.getArgument(0));
        service = new EmailTemplateService(repository, users);
    }

    private static EmailTemplateRequest request(String name, String subject, String body) {
        EmailTemplateRequest r = new EmailTemplateRequest();
        r.setName(name);
        r.setSubject(subject);
        r.setBody(body);
        return r;
    }

    private static EmailTemplate stored(Long id, String name) {
        EmailTemplate t = EmailTemplate.builder()
                .ownerUserId(OWNER_ID).name(name).subject("Subject").body("Body").build();
        t.setId(id);
        return t;
    }

    // ------------------------------------------------------------- saving

    @Test
    void savesATemplateAgainstTheCallersWorkspace() {
        EmailTemplateResponse saved = service.create(request("Diwali offer", "50% off", "Hi {{name}}"));

        assertEquals("Diwali offer", saved.getName());
        assertEquals("50% off", saved.getSubject());
        assertEquals("Hi {{name}}", saved.getBody());
    }

    @Test
    void trimsTheNameAndSubjectButLeavesTheBodyAlone() {
        EmailTemplateResponse saved = service.create(
                request("  Diwali  ", "  50% off  ", "  Hi there  "));

        assertEquals("Diwali", saved.getName());
        assertEquals("50% off", saved.getSubject());
        // Leading space can be deliberate in an email body; only the metadata is tidied.
        assertEquals("  Hi there  ", saved.getBody());
    }

    @Test
    void refusesABlankName() {
        assertThrows(BadRequestException.class, () -> service.create(request("  ", "s", "b")));
        verify(repository, never()).save(any());
    }

    @Test
    void refusesABlankSubject() {
        assertThrows(BadRequestException.class, () -> service.create(request("n", "", "b")));
    }

    @Test
    void refusesABlankBody() {
        assertThrows(BadRequestException.class, () -> service.create(request("n", "s", "   ")));
    }

    @Test
    void refusesASecondTemplateWithTheSameName() {
        when(repository.existsByOwnerUserIdAndNameIgnoreCase(OWNER_ID, "Diwali")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> service.create(request("Diwali", "s", "b")));
        verify(repository, never()).save(any());
    }

    @Test
    void refusesANameLongerThanTheColumn() {
        assertThrows(BadRequestException.class,
                () -> service.create(request("x".repeat(151), "s", "b")));
    }

    @Test
    void refusesASubjectLongerThanTheColumn() {
        assertThrows(BadRequestException.class,
                () -> service.create(request("n", "x".repeat(501), "b")));
    }

    // ------------------------------------------------------------ editing

    @Test
    void updatesTheTemplateInPlace() {
        when(repository.findByIdAndOwnerUserId(7L, OWNER_ID)).thenReturn(Optional.of(stored(7L, "Old")));
        when(repository.findByOwnerUserIdAndNameIgnoreCase(OWNER_ID, "New")).thenReturn(Optional.empty());

        EmailTemplateResponse updated = service.update(7L, request("New", "New subject", "New body"));

        assertEquals("New", updated.getName());
        assertEquals("New subject", updated.getSubject());
        assertEquals("New body", updated.getBody());
    }

    @Test
    void letsATemplateKeepItsOwnNameOnEdit() {
        when(repository.findByIdAndOwnerUserId(7L, OWNER_ID)).thenReturn(Optional.of(stored(7L, "Diwali")));
        when(repository.findByOwnerUserIdAndNameIgnoreCase(OWNER_ID, "Diwali"))
                .thenReturn(Optional.of(stored(7L, "Diwali")));

        EmailTemplateResponse updated = service.update(7L, request("Diwali", "s", "b"));

        assertEquals("Diwali", updated.getName());
    }

    @Test
    void refusesToRenameOntoAnotherTemplate() {
        when(repository.findByIdAndOwnerUserId(7L, OWNER_ID)).thenReturn(Optional.of(stored(7L, "Diwali")));
        when(repository.findByOwnerUserIdAndNameIgnoreCase(OWNER_ID, "Holi"))
                .thenReturn(Optional.of(stored(9L, "Holi")));

        assertThrows(BadRequestException.class, () -> service.update(7L, request("Holi", "s", "b")));
    }

    // ------------------------------------------------- other people's data

    @Test
    void anotherOwnersTemplateReadsAsMissing() {
        when(repository.findByIdAndOwnerUserId(7L, OWNER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(7L));
    }

    @Test
    void anotherOwnersTemplateCannotBeDeleted() {
        when(repository.findByIdAndOwnerUserId(7L, OWNER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(7L));
        verify(repository, never()).delete(any());
    }

    @Test
    void anotherOwnersTemplateCannotBeEdited() {
        when(repository.findByIdAndOwnerUserId(7L, OWNER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.update(7L, request("n", "s", "b")));
    }

    @Test
    void listingOnlyAsksForTheCallersOwnTemplates() {
        when(repository.findByOwnerUserIdOrderByIdDesc(OWNER_ID))
                .thenReturn(List.of(stored(2L, "B"), stored(1L, "A")));

        List<EmailTemplateResponse> list = service.list();

        assertEquals(2, list.size());
        verify(repository).findByOwnerUserIdOrderByIdDesc(OWNER_ID);
    }

    @Test
    void refusesEverythingWhenNobodyIsSignedIn() {
        when(users.currentDataOwnerIdOrNull()).thenReturn(null);

        assertThrows(UnauthorizedException.class, () -> service.list());
        assertThrows(UnauthorizedException.class, () -> service.create(request("n", "s", "b")));
    }

    // ------------------------------------------- what the campaign asks for

    @Test
    void noTemplateIdMeansNoTemplate() {
        assertTrue(service.findForCampaign(null, OWNER_ID).isEmpty());
        verify(repository, never()).findByIdAndOwnerUserId(any(), anyString());
    }

    @Test
    void campaignLookupIsScopedToTheOwnerItIsGiven() {
        EmailTemplate t = stored(7L, "Diwali");
        when(repository.findByIdAndOwnerUserId(7L, OWNER_ID)).thenReturn(Optional.of(t));

        assertSame(t, service.findForCampaign(7L, OWNER_ID).orElseThrow());
        verify(repository).findByIdAndOwnerUserId(eq(7L), eq(OWNER_ID));
    }

    @Test
    void campaignLookupRejectsAnotherOwnersTemplate() {
        when(repository.findByIdAndOwnerUserId(7L, "someone-else")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.findForCampaign(7L, "someone-else"));
    }
}
