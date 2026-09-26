package com.xetax.crm.invoice.tools;

import com.xetax.crm.invoice.Invoice;
import com.xetax.crm.invoice.InvoiceItem;
import com.xetax.crm.invoice.InvoiceService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI tools for invoicing.
 *
 * <p>Money is the part of the panel where a wrong write hurts most, so the
 * split here is deliberate: reading is free, creating an invoice and
 * recording a payment happen only on an explicit request, and nothing sends
 * anything to a customer unless the user says to send it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceTools {

    private static final int PAGE_SIZE = 10;

    private final InvoiceService invoiceService;

    /**
     * One line of an invoice, as the model is allowed to describe it.
     *
     * <p>Deliberately NOT InvoiceService.ItemRequest. The schema generator
     * marks every component of a record as required, so handing the model
     * that type made hsn and unit mandatory — and a model that must supply an
     * HSN code will invent one. A wrong HSN on a GST invoice is the user's
     * compliance problem, not a cosmetic one. Those two fields stay on the
     * Invoices page, where a person types them.
     */
    public record InvoiceLine(String description, Double quantity, Double unitPrice) {
    }

    @Tool(description = """
            READ-ONLY. List the user's invoices, newest first. All arguments
            are optional: status is DRAFT, SENT, PARTIAL, PAID or CANCELLED;
            search matches the customer's name, phone or the invoice number,
            so "Rahul ke invoices" is a search; page is 0-based.
            """)
    @RequiresPermission("invoices.view")
    public Map<String, Object> getMyInvoices(
            @ToolParam(description = "DRAFT/SENT/PARTIAL/PAID/CANCELLED", required = false)
            String status,
            @ToolParam(description = "Customer name, phone or invoice number", required = false)
            String search,
            @ToolParam(description = "0-based page", required = false) Integer page) {
        try {
            var found = invoiceService.list(status, search, null, null,
                    page == null ? 0 : page, PAGE_SIZE);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", found.getTotalElements());
            out.put("page", found.getNumber());
            out.put("invoices", found.getContent().stream().map(InvoiceTools::summaryOf).toList());
            return out;
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            READ-ONLY. The money totals across all of the user's invoices:
            how much has been invoiced, how much received, how much is still
            pending and how much of that is overdue, plus draft/open/paid
            counts. Use this for "how much business did I do", "kitna paisa
            aana baaki hai" and any question about outstanding money.
            """)
    @RequiresPermission("invoices.view")
    public Map<String, Object> getInvoiceSummary() {
        try {
            return invoiceService.summary();
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            READ-ONLY. One invoice in full — line items, tax, totals, what has
            been paid and the payments against it. Argument invoiceId comes
            from getMyInvoices; never ask the user for it.
            """)
    @RequiresPermission("invoices.view")
    public Map<String, Object> getInvoiceDetails(
            @ToolParam(description = "Invoice id from getMyInvoices") Long invoiceId) {
        try {
            Map<String, Object> out = new LinkedHashMap<>(detailOf(invoiceService.get(invoiceId)));
            out.put("payments", invoiceService.paymentsOf(invoiceId));
            return out;
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            READ-ONLY. The invoices raised against ONE record or ONE contact.
            Pass exactly one of recordId (from getRecords/searchRecords) or
            contactId (from searchMyContacts).
            """)
    @RequiresPermission("invoices.view")
    public Map<String, Object> getInvoicesFor(
            @ToolParam(description = "Record id", required = false) String recordId,
            @ToolParam(description = "Contact id", required = false) Long contactId) {
        try {
            List<Invoice> found;
            if (recordId != null && !recordId.isBlank()) {
                found = invoiceService.forRecord(recordId.trim());
            }
            else if (contactId != null) {
                found = invoiceService.forContact(contactId);
            }
            else {
                return Map.of("error", "Pass either a recordId or a contactId.");
            }
            return Map.of("count", found.size(),
                    "invoices", found.stream().map(InvoiceTools::summaryOf).toList());
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Raise a new invoice. Use ONLY when the user explicitly asks
            for one, and confirm the line items and amounts with them first.

            The invoice MUST be attached to exactly one of recordId (from
            getRecords/searchRecords) or contactId (from searchMyContacts) —
            look the customer up first rather than asking for an id. Customer
            name/phone/email are taken from that record or contact when you
            leave them out.

            items is the list of lines, each with a description, a quantity
            and a unitPrice. taxPercent and discount are optional. Dates are
            yyyy-MM-dd. The invoice is created as a DRAFT — it is not sent to
            anyone until the user asks you to send it. HSN codes and units are
            not set from here; if the user needs them, they add them on the
            Invoices page.
            """)
    @RequiresPermission("invoices.manage")
    public Map<String, Object> createInvoice(
            @ToolParam(description = "Line items, each with description, quantity and unitPrice")
            List<InvoiceLine> items,
            @ToolParam(description = "Record id to bill against", required = false) String recordId,
            @ToolParam(description = "Contact id to bill against", required = false) Long contactId,
            @ToolParam(description = "Customer name override", required = false) String customerName,
            @ToolParam(description = "Customer phone override", required = false) String customerPhone,
            @ToolParam(description = "Customer email override", required = false) String customerEmail,
            @ToolParam(description = "Issue date yyyy-MM-dd", required = false) String issueDate,
            @ToolParam(description = "Due date yyyy-MM-dd", required = false) String dueDate,
            @ToolParam(description = "Tax percent, e.g. 18", required = false) Double taxPercent,
            @ToolParam(description = "Flat discount amount", required = false) Double discount,
            @ToolParam(description = "Notes printed on the invoice", required = false) String notes) {
        try {
            if (items == null || items.isEmpty()) {
                return Map.of("created", false, "error", "An invoice needs at least one line item.");
            }
            InvoiceService.InvoiceRequest request = new InvoiceService.InvoiceRequest(
                    contactId, blankToNull(recordId),
                    blankToNull(customerName), blankToNull(customerPhone),
                    blankToNull(customerEmail), null,
                    blankToNull(issueDate), blankToNull(dueDate),
                    taxPercent, discount, blankToNull(notes),
                    null, null, null, null, toItemRequests(items));
            Map<String, Object> out = new LinkedHashMap<>(detailOf(invoiceService.create(request)));
            out.put("created", true);
            out.put("note", "Created as a draft. Ask me to send it, or send it from the "
                    + "Invoices page.");
            return out;
        }
        catch (Exception e) {
            return Map.of("created", false, "error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Record a payment received against an invoice. Use ONLY on
            an explicit request — this changes what the customer owes.
            Argument amount is the money received now, not the total. paidOn
            is yyyy-MM-dd and defaults to today; mode is free text like CASH,
            UPI or BANK.
            """)
    @RequiresPermission("invoices.manage")
    public Map<String, Object> recordInvoicePayment(
            @ToolParam(description = "Invoice id from getMyInvoices") Long invoiceId,
            @ToolParam(description = "Amount received now") Double amount,
            @ToolParam(description = "Payment date yyyy-MM-dd", required = false) String paidOn,
            @ToolParam(description = "CASH / UPI / BANK / CHEQUE", required = false) String mode,
            @ToolParam(description = "Reference or transaction number", required = false)
            String reference) {
        try {
            Invoice updated = invoiceService.recordPayment(invoiceId,
                    new InvoiceService.PaymentRequest(amount, blankToNull(paidOn),
                            blankToNull(mode), blankToNull(reference), null));
            Map<String, Object> out = new LinkedHashMap<>(summaryOf(updated));
            out.put("recorded", true);
            return out;
        }
        catch (Exception e) {
            return Map.of("recorded", false, "error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Send an invoice to its customer. channel is WHATSAPP (needs
            the customer's phone and the user's WhatsApp connected) or EMAIL
            (needs the customer's email). Use ONLY when the user explicitly
            asks to send it — this reaches the customer.
            """)
    @RequiresPermission("invoices.manage")
    public Map<String, Object> sendInvoice(
            @ToolParam(description = "Invoice id from getMyInvoices") Long invoiceId,
            @ToolParam(description = "WHATSAPP or EMAIL") String channel) {
        try {
            Invoice sent = invoiceService.send(invoiceId, channel);
            Map<String, Object> out = new LinkedHashMap<>(summaryOf(sent));
            out.put("sent", true);
            return out;
        }
        catch (Exception e) {
            return Map.of("sent", false, "error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Cancel an invoice. Use ONLY on an explicit request. A
            cancelled invoice stops counting towards the money totals; it is
            not deleted.
            """)
    @RequiresPermission("invoices.manage")
    public Map<String, Object> cancelInvoice(
            @ToolParam(description = "Invoice id from getMyInvoices") Long invoiceId) {
        try {
            Invoice cancelled = invoiceService.cancel(invoiceId);
            Map<String, Object> out = new LinkedHashMap<>(summaryOf(cancelled));
            out.put("cancelled", true);
            return out;
        }
        catch (Exception e) {
            return Map.of("cancelled", false, "error", safeMessage(e));
        }
    }

    private static Map<String, Object> summaryOf(Invoice invoice) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", invoice.getId());
        map.put("number", invoice.getNumber());
        map.put("customerName", invoice.getCustomerName());
        map.put("status", invoice.getStatus());
        map.put("issueDate", String.valueOf(invoice.getIssueDate()));
        map.put("dueDate", String.valueOf(invoice.getDueDate()));
        map.put("total", invoice.getTotal());
        map.put("amountPaid", invoice.getAmountPaid());
        map.put("balance", Math.max(0, invoice.getTotal() - invoice.getAmountPaid()));
        return map;
    }

    private static Map<String, Object> detailOf(Invoice invoice) {
        Map<String, Object> map = new LinkedHashMap<>(summaryOf(invoice));
        map.put("subtotal", invoice.getSubtotal());
        map.put("taxPercent", invoice.getTaxPercent());
        map.put("taxAmount", invoice.getTaxAmount());
        map.put("discount", invoice.getDiscount());
        map.put("notes", invoice.getNotes());
        map.put("customerPhone", invoice.getCustomerPhone());
        map.put("customerEmail", invoice.getCustomerEmail());
        map.put("recordId", invoice.getRecordId());
        map.put("contactId", invoice.getContactId());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (InvoiceItem item : invoice.getItems()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("description", item.getDescription());
            line.put("quantity", item.getQuantity());
            line.put("unitPrice", item.getUnitPrice());
            line.put("amount", item.getAmount());
            lines.add(line);
        }
        map.put("items", lines);
        return map;
    }

    /** HSN and unit are left unset on purpose — see {@link InvoiceLine}. */
    private static List<InvoiceService.ItemRequest> toItemRequests(List<InvoiceLine> lines) {
        List<InvoiceService.ItemRequest> items = new ArrayList<>(lines.size());
        for (InvoiceLine line : lines) {
            items.add(new InvoiceService.ItemRequest(
                    line.description(), line.quantity(), line.unitPrice(), null, null));
        }
        return items;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? "The invoice action could not be completed." : message;
    }
}
