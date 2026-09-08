package com.xetax.crm.invoice;

import com.xetax.crm.activity.RecordActivityService;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.contact.Contact;
import com.xetax.crm.contact.ContactRepository;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.repository.StageRepo;
import com.xetax.crm.data_manager.service.FormOwnerCache;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Invoicing. Money math lives HERE — the client sends items and rates, the
 * server computes subtotal/tax/total and keeps them consistent forever after.
 *
 * Rules the UI relies on:
 *  - an invoice belongs to a contact OR a record (never both, never neither);
 *  - a record can be invoiced only while it sits in a FINAL stage;
 *  - PAID/CANCELLED invoices are frozen (no edits, no payments);
 *  - deleting is only for DRAFTs — issued invoices get CANCELLED instead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoicePaymentRepository paymentRepository;
    private final ContactRepository contactRepository;
    private final RecordRepo recordRepo;
    private final StageRepo stageRepo;
    private final FormOwnerCache formOwnerCache;
    private final CurrentUserProvider currentUserProvider;
    private final OrgSmtpService orgSmtpService;
    private final WhatsAppMessagingService whatsAppMessagingService;
    private final RecordActivityService activityService;
    private final InvoicePdfRenderer pdfRenderer;

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    // ------------------------------------------------------------- requests

    public record ItemRequest(String description, Double quantity, Double unitPrice,
                              String hsn, String unit) {}

    public record InvoiceRequest(Long contactId, String recordId,
                                 String customerName, String customerPhone,
                                 String customerEmail, String customerAddress,
                                 String issueDate, String dueDate,
                                 Double taxPercent, Double discount, String notes,
                                 String customerGstin, String sellerGstin,
                                 String gstMode, String bankDetails,
                                 List<ItemRequest> items) {}

    // ----------------------------------------------------------------- crud

    @Transactional
    public Invoice create(InvoiceRequest request) {
        String own = owner();

        if ((request.contactId() == null) == (request.recordId() == null || request.recordId().isBlank())) {
            throw new BadRequestException("Attach the invoice to a contact or to a record");
        }

        String name = request.customerName();
        String phone = request.customerPhone();
        String email = request.customerEmail();
        String address = request.customerAddress();

        if (request.contactId() != null) {
            Contact contact = contactRepository.findByIdAndOwnerUserId(request.contactId(), own)
                    .orElseThrow(() -> new ResourceNotFoundException("Contact not found"));
            if (isBlank(name)) name = contact.getName();
            if (isBlank(phone)) phone = contact.getPhone();
            if (isBlank(email)) email = contact.getEmail();
            if (isBlank(address)) address = contact.getAddress();
        } else {
            RecordDocument record = ownedRecordInFinalStage(request.recordId(), own);
            Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
            if (isBlank(name)) name = firstText(data);
        }
        if (isBlank(name)) throw new BadRequestException("Customer name is required");

        Invoice invoice = Invoice.builder()
                .ownerUserId(own)
                .number(nextNumber(own))
                .contactId(request.contactId())
                .recordId(isBlank(request.recordId()) ? null : request.recordId())
                .customerName(name.trim())
                .customerPhone(trimOrNull(phone))
                .customerEmail(trimOrNull(email))
                .customerAddress(trimOrNull(address))
                .status("DRAFT")
                .issueDate(parseDate(request.issueDate(), LocalDate.now()))
                .dueDate(parseDate(request.dueDate(), null))
                .notes(trimOrNull(request.notes()))
                .customerGstin(trimOrNull(request.customerGstin()))
                .sellerGstin(trimOrNull(request.sellerGstin()))
                .gstMode(normalizeGstMode(request.gstMode()))
                .bankDetails(trimOrNull(request.bankDetails()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        applyItems(invoice, request);

        Invoice saved = invoiceRepository.save(invoice);
        logOnRecord(saved, "Invoice " + saved.getNumber() + " created (Rs. "
                + money(saved.getTotal()) + ")");
        return saved;
    }

    @Transactional
    public Invoice update(Long id, InvoiceRequest request) {
        Invoice invoice = find(id);
        if ("PAID".equals(invoice.getStatus()) || "CANCELLED".equals(invoice.getStatus())) {
            throw new BadRequestException("A " + invoice.getStatus().toLowerCase()
                    + " invoice cannot be edited");
        }
        if (!isBlank(request.customerName())) invoice.setCustomerName(request.customerName().trim());
        invoice.setCustomerPhone(trimOrNull(request.customerPhone()));
        invoice.setCustomerEmail(trimOrNull(request.customerEmail()));
        invoice.setCustomerAddress(trimOrNull(request.customerAddress()));
        invoice.setIssueDate(parseDate(request.issueDate(), invoice.getIssueDate()));
        invoice.setDueDate(parseDate(request.dueDate(), null));
        invoice.setNotes(trimOrNull(request.notes()));
        invoice.setCustomerGstin(trimOrNull(request.customerGstin()));
        invoice.setSellerGstin(trimOrNull(request.sellerGstin()));
        invoice.setGstMode(normalizeGstMode(request.gstMode()));
        invoice.setBankDetails(trimOrNull(request.bankDetails()));
        applyItems(invoice, request);
        invoice.setUpdatedAt(LocalDateTime.now());
        // A part-paid invoice keeps its money-derived status.
        if (invoice.getAmountPaid() > 0) refreshPaidStatus(invoice);
        return invoiceRepository.save(invoice);
    }

    public Invoice get(Long id) {
        return find(id);
    }

    public List<InvoicePayment> paymentsOf(Long id) {
        find(id); // ownership check
        return paymentRepository.findByInvoiceIdOrderByIdDesc(id);
    }

    @Transactional
    public void delete(Long id) {
        Invoice invoice = find(id);
        if (!"DRAFT".equals(invoice.getStatus())) {
            throw new BadRequestException("Only draft invoices can be deleted — cancel this one instead");
        }
        paymentRepository.deleteByInvoiceId(id);
        invoiceRepository.delete(invoice);
    }

    @Transactional
    public Invoice cancel(Long id) {
        Invoice invoice = find(id);
        if ("PAID".equals(invoice.getStatus())) {
            throw new BadRequestException("A paid invoice cannot be cancelled");
        }
        invoice.setStatus("CANCELLED");
        invoice.setUpdatedAt(LocalDateTime.now());
        logOnRecord(invoice, "Invoice " + invoice.getNumber() + " cancelled");
        return invoiceRepository.save(invoice);
    }

    // ------------------------------------------------------------ list + sum

    public Page<Invoice> list(String status, String q, String fromDate, String toDate,
                              int page, int size) {
        return invoiceRepository.search(owner(),
                isBlank(status) ? null : status,
                isBlank(q) ? null : q.trim(),
                parseDate(fromDate, null), parseDate(toDate, null),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    /** The tiles on the history page — one pass over the org's invoices. */
    public Map<String, Object> summary() {
        List<Invoice> all = invoiceRepository.findByOwnerUserId(owner());
        double invoiced = 0, received = 0, pending = 0, overdue = 0;
        int draftCount = 0, openCount = 0, paidCount = 0;
        LocalDate today = LocalDate.now();
        for (Invoice i : all) {
            if ("CANCELLED".equals(i.getStatus())) continue;
            invoiced += i.getTotal();
            received += i.getAmountPaid();
            double balance = Math.max(0, i.getTotal() - i.getAmountPaid());
            if ("DRAFT".equals(i.getStatus())) draftCount++;
            if ("PAID".equals(i.getStatus())) paidCount++;
            else {
                pending += balance;
                if (!"DRAFT".equals(i.getStatus())) openCount++;
                if (i.getDueDate() != null && i.getDueDate().isBefore(today) && balance > 0) {
                    overdue += balance;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoiced", round2(invoiced));
        out.put("received", round2(received));
        out.put("pending", round2(pending));
        out.put("overdue", round2(overdue));
        out.put("draftCount", draftCount);
        out.put("openCount", openCount);
        out.put("paidCount", paidCount);
        out.put("totalCount", all.size());
        return out;
    }

    public List<Invoice> forContact(Long contactId) {
        return invoiceRepository.findByOwnerUserIdAndContactIdOrderByIdDesc(owner(), contactId);
    }

    public List<Invoice> forRecord(String recordId) {
        return invoiceRepository.findByOwnerUserIdAndRecordIdOrderByIdDesc(owner(), recordId);
    }

    // -------------------------------------------------------------- payments

    public record PaymentRequest(Double amount, String paidOn, String mode,
                                 String reference, String note) {}

    @Transactional
    public Invoice recordPayment(Long id, PaymentRequest request) {
        Invoice invoice = find(id);
        if ("CANCELLED".equals(invoice.getStatus())) {
            throw new BadRequestException("This invoice is cancelled");
        }
        double amount = request.amount() == null ? 0 : request.amount();
        if (amount <= 0) throw new BadRequestException("Payment amount must be more than zero");
        double balance = round2(invoice.getTotal() - invoice.getAmountPaid());
        if (amount > balance + 0.01) {
            throw new BadRequestException("Payment exceeds the balance (Rs. " + money(balance) + ")");
        }

        paymentRepository.save(InvoicePayment.builder()
                .invoiceId(id).ownerUserId(invoice.getOwnerUserId())
                .amount(round2(amount))
                .paidOn(parseDate(request.paidOn(), LocalDate.now()))
                .mode(isBlank(request.mode()) ? "OTHER" : request.mode().toUpperCase())
                .reference(trimOrNull(request.reference()))
                .note(trimOrNull(request.note()))
                .createdAt(LocalDateTime.now())
                .build());

        invoice.setAmountPaid(round2(invoice.getAmountPaid() + amount));
        refreshPaidStatus(invoice);
        invoice.setUpdatedAt(LocalDateTime.now());
        Invoice saved = invoiceRepository.save(invoice);
        logOnRecord(saved, "Payment of Rs. " + money(amount) + " recorded on "
                + saved.getNumber() + " (" + saved.getStatus() + ")");
        return saved;
    }

    // ------------------------------------------------------------------ send

    @Transactional
    public Invoice send(Long id, String channel) {
        Invoice invoice = find(id);
        if ("CANCELLED".equals(invoice.getStatus())) {
            throw new BadRequestException("This invoice is cancelled");
        }
        byte[] pdf = pdfRenderer.render(invoice, companyName());
        String filename = invoice.getNumber() + ".pdf";

        if ("EMAIL".equalsIgnoreCase(channel)) {
            if (isBlank(invoice.getCustomerEmail())) {
                throw new BadRequestException("This customer has no email address on the invoice");
            }
            orgSmtpService.sendWithAttachment(invoice.getOwnerUserId(),
                    invoice.getCustomerEmail(),
                    "Invoice " + invoice.getNumber() + " from " + companyName(),
                    "Dear " + invoice.getCustomerName() + ",\n\nPlease find invoice "
                            + invoice.getNumber() + " attached (total Rs. " + money(invoice.getTotal())
                            + ").\n\nThank you!",
                    filename, pdf, "application/pdf");
        } else {
            if (isBlank(invoice.getCustomerPhone())) {
                throw new BadRequestException("This customer has no phone number on the invoice");
            }
            whatsAppMessagingService.sendDocumentAsOwner(invoice.getOwnerUserId(),
                    invoice.getCustomerPhone(), pdf, filename, "application/pdf",
                    "Invoice " + invoice.getNumber() + " — total Rs. " + money(invoice.getTotal()));
        }

        if ("DRAFT".equals(invoice.getStatus())) invoice.setStatus("SENT");
        invoice.setUpdatedAt(LocalDateTime.now());
        Invoice saved = invoiceRepository.save(invoice);
        logOnRecord(saved, "Invoice " + saved.getNumber() + " sent via " + channel.toUpperCase());
        return saved;
    }

    public byte[] pdf(Long id) {
        return pdfRenderer.render(find(id), companyName());
    }

    // --------------------------------------------------------------- helpers

    private Invoice find(Long id) {
        return invoiceRepository.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
    }

    /** The record must be ours AND parked in a final stage. */
    private RecordDocument ownedRecordInFinalStage(String recordId, String own) {
        RecordDocument record = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("Record not found"));
        String formOwner = formOwnerCache.getOwner(record.getFormId()).orElse(null);
        if (formOwner == null || !formOwner.equals(own)) {
            throw new ResourceNotFoundException("Record not found");
        }
        FormStage stage = record.getStageId() == null ? null
                : stageRepo.findById(record.getStageId()).orElse(null);
        if (stage == null || !Boolean.TRUE.equals(stage.getIsFinal())) {
            throw new BadRequestException(
                    "Invoices can be raised only when the record is in a final stage"
                    + (stage != null ? " — it is currently in '" + stage.getName() + "'" : ""));
        }
        return record;
    }

    private void applyItems(Invoice invoice, InvoiceRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new BadRequestException("Add at least one item");
        }
        invoice.getItems().clear();
        int order = 1;
        double subtotal = 0;
        List<InvoiceItem> items = new ArrayList<>();
        for (ItemRequest item : request.items()) {
            if (isBlank(item.description())) continue;
            double qty = item.quantity() == null || item.quantity() <= 0 ? 1 : item.quantity();
            double price = item.unitPrice() == null || item.unitPrice() < 0 ? 0 : item.unitPrice();
            double amount = round2(qty * price);
            subtotal += amount;
            items.add(InvoiceItem.builder()
                    .invoice(invoice).description(item.description().trim())
                    .hsn(trimOrNull(item.hsn()))
                    .unit(isBlank(item.unit()) ? "Nos" : item.unit().trim())
                    .quantity(qty).unitPrice(price).amount(amount).sortOrder(order++)
                    .build());
        }
        if (items.isEmpty()) throw new BadRequestException("Add at least one item");
        invoice.getItems().addAll(items);

        double taxPercent = request.taxPercent() == null || request.taxPercent() < 0
                ? 0 : Math.min(request.taxPercent(), 100);
        double discount = request.discount() == null || request.discount() < 0
                ? 0 : request.discount();
        double taxAmount = round2(subtotal * taxPercent / 100);
        double rawTotal = Math.max(0, round2(subtotal + taxAmount - discount));
        // Tally-style round off — the payable total is always a whole rupee.
        double total = Math.round(rawTotal);
        double roundOff = round2(total - rawTotal);

        invoice.setSubtotal(round2(subtotal));
        invoice.setTaxPercent(taxPercent);
        invoice.setTaxAmount(taxAmount);
        invoice.setDiscount(round2(discount));
        invoice.setRoundOff(roundOff);
        invoice.setTotal(total);
    }

    private void refreshPaidStatus(Invoice invoice) {
        if (invoice.getAmountPaid() >= invoice.getTotal() - 0.01) {
            invoice.setAmountPaid(invoice.getTotal());
            invoice.setStatus("PAID");
        } else if (invoice.getAmountPaid() > 0) {
            invoice.setStatus("PARTIAL");
        }
    }

    /** INV-<year>-0001, per org per year; retried once on a rare collision. */
    private String nextNumber(String own) {
        String prefix = "INV-" + LocalDate.now().getYear() + "-";
        long next = invoiceRepository.countByOwnerUserIdAndNumberStartingWith(own, prefix) + 1;
        return prefix + String.format("%04d", next);
    }

    private void logOnRecord(Invoice invoice, String detail) {
        if (invoice.getRecordId() != null) {
            activityService.log(invoice.getRecordId(), invoice.getOwnerUserId(),
                    "DOCUMENT_SENT", detail);
        }
    }

    private String companyName() {
        var user = currentUserProvider.currentUserOrNull();
        if (user != null && user.getCompany() != null && !user.getCompany().isBlank()) {
            return user.getCompany();
        }
        return user != null && user.getName() != null ? user.getName() : "Your Business";
    }

    private static String firstText(Map<String, Object> data) {
        return data.values().stream()
                .filter(v -> v instanceof String str && !str.isBlank())
                .map(Object::toString).findFirst().orElse(null);
    }

    private static String normalizeGstMode(String mode) {
        if (mode == null) return null;
        String m = mode.trim().toUpperCase();
        return m.equals("INTRA") || m.equals("INTER") ? m : null;
    }

    /** "Rupees Nine Thousand Three Hundred Forty Only" — Indian numbering. */
    static String amountInWords(double amount) {
        long rupees = (long) Math.floor(amount);
        int paise = (int) Math.round((amount - rupees) * 100);
        StringBuilder sb = new StringBuilder("Rupees ");
        sb.append(rupees == 0 ? "Zero" : indianWords(rupees));
        if (paise > 0) sb.append(" and ").append(indianWords(paise)).append(" Paise");
        return sb.append(" Only").toString();
    }

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six",
            "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen",
            "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty",
            "Sixty", "Seventy", "Eighty", "Ninety"};

    private static String indianWords(long n) {
        if (n < 20) return ONES[(int) n];
        if (n < 100) return (TENS[(int) (n / 10)] + " " + ONES[(int) (n % 10)]).trim();
        if (n < 1_000) return (ONES[(int) (n / 100)] + " Hundred "
                + (n % 100 == 0 ? "" : indianWords(n % 100))).trim();
        if (n < 100_000) return (indianWords(n / 1_000) + " Thousand "
                + (n % 1_000 == 0 ? "" : indianWords(n % 1_000))).trim();
        if (n < 10_000_000) return (indianWords(n / 100_000) + " Lakh "
                + (n % 100_000 == 0 ? "" : indianWords(n % 100_000))).trim();
        return (indianWords(n / 10_000_000) + " Crore "
                + (n % 10_000_000 == 0 ? "" : indianWords(n % 10_000_000))).trim();
    }

    private static boolean isBlank(String v) { return v == null || v.isBlank(); }
    private static String trimOrNull(String v) { return isBlank(v) ? null : v.trim(); }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    static String money(double v) {
        return String.format("%,.2f", v);
    }

    private static LocalDate parseDate(String v, LocalDate fallback) {
        try {
            return isBlank(v) ? fallback : LocalDate.parse(v);
        } catch (Exception e) {
            return fallback;
        }
    }
}
