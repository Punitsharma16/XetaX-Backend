package com.xetax.crm.invoice;

import com.xetax.crm.invoice.tools.InvoiceTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Invoices page had no tools at all, so every money question ("kitna
 * paisa aana baaki hai") was met with a guess or a refusal.
 */
class InvoiceToolsTest {

    private InvoiceService invoiceService;
    private InvoiceTools tools;

    @BeforeEach
    void setUp() {
        invoiceService = mock(InvoiceService.class);
        tools = new InvoiceTools(invoiceService);

        Invoice created = new Invoice();
        created.setId(11L);
        created.setNumber("INV-0011");
        created.setStatus("DRAFT");
        created.setTotal(90000);
        when(invoiceService.create(any())).thenReturn(created);
    }

    private InvoiceService.InvoiceRequest captureRequest() {
        ArgumentCaptor<InvoiceService.InvoiceRequest> captor =
                ArgumentCaptor.forClass(InvoiceService.InvoiceRequest.class);
        verify(invoiceService).create(captor.capture());
        return captor.getValue();
    }

    @Test
    void itRaisesTheInvoiceAgainstTheRecordItWasToldAbout() {
        Map<String, Object> result = tools.createInvoice(
                List.of(new InvoiceTools.InvoiceLine("Split AC 1.5T", 2.0, 45000.0)),
                "rec-9", null, null, null, null, null, null, 18.0, null, null);

        assertEquals(true, result.get("created"));
        InvoiceService.InvoiceRequest request = captureRequest();
        assertEquals("rec-9", request.recordId());
        assertNull(request.contactId());
        assertEquals(1, request.items().size());
        assertEquals("Split AC 1.5T", request.items().get(0).description());
        assertEquals(45000.0, request.items().get(0).unitPrice());
        assertEquals(18.0, request.taxPercent());
    }

    @Test
    void theModelIsNeverAskedForAnHsnCodeSoItCannotInventOne() {
        // Spring AI marks every component of a nested record as required. With
        // InvoiceService.ItemRequest passed straight through, the model HAD to
        // supply an hsn and a unit, and it would make them up. A wrong HSN on
        // a GST invoice is the user's compliance problem.
        tools.createInvoice(List.of(new InvoiceTools.InvoiceLine("Consulting", 1.0, 5000.0)),
                null, 4L, null, null, null, null, null, null, null, null);

        InvoiceService.ItemRequest line = captureRequest().items().get(0);
        assertNull(line.hsn());
        assertNull(line.unit());
    }

    @Test
    void anInvoiceWithNoLinesIsRefusedBeforeItReachesTheService() {
        Map<String, Object> result = tools.createInvoice(List.of(), "rec-9", null, null, null,
                null, null, null, null, null, null);

        assertEquals(false, result.get("created"));
        verify(invoiceService, never()).create(any());
    }

    @Test
    void theMoneyTotalsComeStraightFromTheSameServiceThePageUses() {
        when(invoiceService.summary()).thenReturn(Map.of(
                "invoiced", 250000.0, "received", 100000.0,
                "pending", 150000.0, "overdue", 40000.0));

        Map<String, Object> result = tools.getInvoiceSummary();

        assertEquals(150000.0, result.get("pending"));
        assertEquals(40000.0, result.get("overdue"));
    }

    @Test
    void askingForOneRecordsInvoicesNeedsExactlyOneOfTheTwoIds() {
        Map<String, Object> neither = tools.getInvoicesFor(null, null);

        assertTrue(String.valueOf(neither.get("error")).contains("recordId"));
        verify(invoiceService, never()).forRecord(any());
        verify(invoiceService, never()).forContact(any());
    }

    @Test
    void aRefusalFromTheServiceIsRelayedRatherThanThrown() {
        // "Attach the invoice to a contact or to a record" is a real message
        // the user can act on; an exception here would surface as a generic
        // failure and the assistant would have nothing to say.
        org.mockito.Mockito.doThrow(new RuntimeException("Attach the invoice to a contact or to a record"))
                .when(invoiceService).create(any());

        Map<String, Object> result = tools.createInvoice(
                List.of(new InvoiceTools.InvoiceLine("Consulting", 1.0, 5000.0)),
                null, null, null, null, null, null, null, null, null, null);

        assertEquals(false, result.get("created"));
        assertEquals("Attach the invoice to a contact or to a record", result.get("error"));
    }

    @Test
    void aPaymentReportsTheBalanceThatIsLeft() {
        Invoice paid = new Invoice();
        paid.setId(11L);
        paid.setNumber("INV-0011");
        paid.setStatus("PARTIAL");
        paid.setTotal(90000);
        paid.setAmountPaid(10000);
        when(invoiceService.recordPayment(any(), any())).thenReturn(paid);

        Map<String, Object> result = tools.recordInvoicePayment(11L, 10000.0, null, "UPI", null);

        assertEquals(true, result.get("recorded"));
        assertEquals(80000.0, result.get("balance"));
    }
}
