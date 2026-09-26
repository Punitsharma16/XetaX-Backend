package com.xetax.crm.aitools;

import com.xetax.crm.booking.service.BookingService;
import com.xetax.crm.booking.tools.BookingTools;
import com.xetax.crm.dashboard.DashboardService;
import com.xetax.crm.dashboard.tools.DashboardTools;
import com.xetax.crm.document.DocumentService;
import com.xetax.crm.document.tools.DocumentTools;
import com.xetax.crm.emailcampaign.dto.EmailCampaignCreateRequest;
import com.xetax.crm.emailcampaign.dto.EmailCampaignResponse;
import com.xetax.crm.emailcampaign.service.EmailCampaignService;
import com.xetax.crm.emailcampaign.tools.EmailCampaignTools;
import com.xetax.crm.menu.service.MenuService;
import com.xetax.crm.menu.tools.MenuTools;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The panel pages that had no AI tools at all: Bookings, Documents, Email
 * Campaigns, the Dashboard and the Online Menu. Asked about any of them the
 * assistant either guessed or said it could not help.
 */
class NewModuleToolsTest {

    private static EmailCampaignResponse campaign(Long id, String name, String status) {
        return EmailCampaignResponse.builder()
                .id(id).name(name).subject("25% off").body("Hi {name}")
                .sourceType("FORM").status(status).targetDescription("leads")
                .build();
    }

    // ------------------------------------------------------------- bookings

    @Test
    void bookingsDefaultToTheWeekAheadWhenNoDatesAreGiven() {
        BookingService bookings = mock(BookingService.class);
        when(bookings.slots(any(), any())).thenReturn(List.of(Map.of("id", 1)));

        Map<String, Object> result = new BookingTools(bookings).getBookingSlots(null, null);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(bookings).slots(from.capture(), to.capture());
        assertEquals(LocalDate.now(), from.getValue());
        assertEquals(LocalDate.now().plusDays(7), to.getValue());
        assertEquals(1, result.get("count"));
    }

    @Test
    void aDateThatIsNotADateIsReportedRatherThanGuessedAt() {
        BookingService bookings = mock(BookingService.class);

        Map<String, Object> result = new BookingTools(bookings).getBookingSlots("kal", null);

        assertTrue(String.valueOf(result.get("error")).contains("yyyy-MM-dd"));
        verify(bookings, never()).slots(any(), any());
    }

    // ------------------------------------------------------------ documents

    @Test
    void sendingADocumentPassesTheWholeContextThroughUntouched() {
        DocumentService documents = mock(DocumentService.class);

        Map<String, Object> result = new DocumentTools(documents)
                .sendDocument(5L, "WHATSAPP", null, null, null, "Bhej raha hoon",
                        "rec-9", null, true);

        assertEquals(true, result.get("sent"));
        ArgumentCaptor<DocumentService.SendRequest> sent =
                ArgumentCaptor.forClass(DocumentService.SendRequest.class);
        verify(documents).send(eq(5L), sent.capture());
        assertEquals("WHATSAPP", sent.getValue().channel());
        assertEquals("rec-9", sent.getValue().recordId());
        assertEquals(Boolean.TRUE, sent.getValue().personalize());
    }

    @Test
    void anAbsentPersonaliseFlagIsFalseNotNull() {
        DocumentService documents = mock(DocumentService.class);

        new DocumentTools(documents)
                .sendDocument(5L, "EMAIL", null, "a@b.com", "Brochure", null, null, null, null);

        ArgumentCaptor<DocumentService.SendRequest> sent =
                ArgumentCaptor.forClass(DocumentService.SendRequest.class);
        verify(documents).send(eq(5L), sent.capture());
        assertEquals(Boolean.FALSE, sent.getValue().personalize());
        assertNull(sent.getValue().phone());
    }

    // ------------------------------------------------------- email campaigns

    @Test
    void anEmailCampaignIsOnlyEverCreatedAsADraft() {
        EmailCampaignService campaigns = mock(EmailCampaignService.class);
        when(campaigns.create(any())).thenReturn(campaign(2L, "Diwali offer", "DRAFT"));

        Map<String, Object> result = new EmailCampaignTools(campaigns).createEmailCampaignDraft(
                "Diwali offer", "25% off", "Hi {name}", "leads", "email", null);

        assertEquals(true, result.get("created"));
        assertEquals("DRAFT", result.get("status"));
        assertTrue(String.valueOf(result.get("note")).contains("I do not start campaigns"));

        ArgumentCaptor<EmailCampaignCreateRequest> request =
                ArgumentCaptor.forClass(EmailCampaignCreateRequest.class);
        verify(campaigns).create(request.capture());
        assertEquals("leads", request.getValue().getFormSlug());
        assertEquals("email", request.getValue().getEmailFieldKey());
        // Nothing in this tool set can start a send.
        verify(campaigns, never()).start(any(), any());
    }

    @Test
    void stoppingACampaignAcceptsOnlyTheThreeSafeActions() {
        EmailCampaignService campaigns = mock(EmailCampaignService.class);
        when(campaigns.pause(1L)).thenReturn(campaign(1L, "Diwali offer", "PAUSED"));
        EmailCampaignTools tools = new EmailCampaignTools(campaigns);

        assertEquals(true, tools.setEmailCampaignState(1L, "pause").get("updated"));
        verify(campaigns).pause(1L);

        Map<String, Object> bad = tools.setEmailCampaignState(1L, "START");
        assertEquals(false, bad.get("updated"));
        verify(campaigns, never()).start(any(), any());
    }

    // ------------------------------------------------------------ dashboard

    @Test
    void theDashboardAnswerIsTheSameOneThePageDraws() {
        DashboardService dashboard = mock(DashboardService.class);
        when(dashboard.summary()).thenReturn(Map.of("records", 225, "forms", 4));

        Map<String, Object> result = new DashboardTools(dashboard).getDashboardSummary();

        assertEquals(225, result.get("records"));
    }

    @Test
    void aDashboardOutageIsAMessageNotAStackTrace() {
        DashboardService dashboard = mock(DashboardService.class);
        when(dashboard.summary()).thenThrow(new RuntimeException("Database is down"));

        assertEquals("Database is down",
                new DashboardTools(dashboard).getDashboardSummary().get("error"));
    }

    // ----------------------------------------------------------------- menu

    @Test
    void markingADishSoldOutChangesOnlyItsAvailability() {
        // "paneer tikka aaj band kar do" must not touch the price, and must
        // not delete the dish.
        MenuService menu = mock(MenuService.class);
        when(menu.updateItem(any(), any())).thenReturn(Map.of("id", 4, "available", false));

        Map<String, Object> result =
                new MenuTools(menu).updateMenuItem(4L, null, null, null, false);

        assertEquals(true, result.get("updated"));
        ArgumentCaptor<MenuService.ItemInput> input =
                ArgumentCaptor.forClass(MenuService.ItemInput.class);
        verify(menu).updateItem(eq(4L), input.capture());
        assertEquals(Boolean.FALSE, input.getValue().available());
        assertNull(input.getValue().price(), "the price must be left alone");
        assertNull(input.getValue().name());
        verify(menu, never()).deleteItem(any());
    }

    @Test
    void aNewDishCarriesItsPriceAsADecimal() {
        MenuService menu = mock(MenuService.class);
        when(menu.createItem(any())).thenReturn(Map.of("id", 9));

        new MenuTools(menu).createMenuItem(2L, "Paneer Tikka", 320.0, null, 280.0, true);

        ArgumentCaptor<MenuService.ItemInput> input =
                ArgumentCaptor.forClass(MenuService.ItemInput.class);
        verify(menu).createItem(input.capture());
        assertEquals(0, input.getValue().price().compareTo(new java.math.BigDecimal("320.0")));
        assertEquals(0, input.getValue().offerPrice().compareTo(new java.math.BigDecimal("280.0")));
        assertEquals(Boolean.TRUE, input.getValue().veg());
        assertNotNull(input.getValue().categoryId());
    }
}
