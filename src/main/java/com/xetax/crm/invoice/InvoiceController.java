package com.xetax.crm.invoice;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoiceAiService invoiceAiService;
    private final com.xetax.crm.auth.security.CurrentUserProvider currentUserProvider;

    @GetMapping
    @RequiresPermission("invoices.view")
    public ApiResponse<Page<Invoice>> list(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String fromDate,
                                           @RequestParam(required = false) String toDate,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return ResponseUtil.success("Invoices",
                invoiceService.list(status, q, fromDate, toDate, page, size));
    }

    @GetMapping("/summary")
    @RequiresPermission("invoices.view")
    public ApiResponse<Map<String, Object>> summary() {
        return ResponseUtil.success("Summary", invoiceService.summary());
    }

    @GetMapping("/{id:\\d+}")
    @RequiresPermission("invoices.view")
    public ApiResponse<Invoice> get(@PathVariable Long id) {
        return ResponseUtil.success("Invoice", invoiceService.get(id));
    }

    @GetMapping("/{id}/payments")
    @RequiresPermission("invoices.view")
    public ApiResponse<List<InvoicePayment>> payments(@PathVariable Long id) {
        return ResponseUtil.success("Payments", invoiceService.paymentsOf(id));
    }

    @GetMapping("/for-contact/{contactId}")
    @RequiresPermission("invoices.view")
    public ApiResponse<List<Invoice>> forContact(@PathVariable Long contactId) {
        return ResponseUtil.success("Invoices", invoiceService.forContact(contactId));
    }

    @GetMapping("/for-record/{recordId}")
    @RequiresPermission("invoices.view")
    public ApiResponse<List<Invoice>> forRecord(@PathVariable String recordId) {
        return ResponseUtil.success("Invoices", invoiceService.forRecord(recordId));
    }

    @PostMapping
    @RequiresPermission("invoices.manage")
    public ApiResponse<Invoice> create(@RequestBody InvoiceService.InvoiceRequest request) {
        return ResponseUtil.success("Invoice created", invoiceService.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("invoices.manage")
    public ApiResponse<Invoice> update(@PathVariable Long id,
                                       @RequestBody InvoiceService.InvoiceRequest request) {
        return ResponseUtil.success("Invoice updated", invoiceService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("invoices.manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        invoiceService.delete(id);
        return ResponseUtil.success("Invoice deleted", null);
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission("invoices.manage")
    public ApiResponse<Invoice> cancel(@PathVariable Long id) {
        return ResponseUtil.success("Invoice cancelled", invoiceService.cancel(id));
    }

    @PostMapping("/{id}/payments")
    @RequiresPermission("invoices.manage")
    public ApiResponse<Invoice> recordPayment(@PathVariable Long id,
                                              @RequestBody InvoiceService.PaymentRequest request) {
        return ResponseUtil.success("Payment recorded", invoiceService.recordPayment(id, request));
    }

    public record AiDraftRequest(String prompt) {}

    @PostMapping("/ai-draft")
    @RequiresPermission("invoices.manage")
    public ApiResponse<Map<String, Object>> aiDraft(@RequestBody AiDraftRequest request) {
        var owner = currentUserProvider.currentDataOwnerIdOrNull();
        return ResponseUtil.success("Draft ready",
                invoiceAiService.draft(owner == null ? "" : owner.toString(), request.prompt()));
    }

    public record SendRequest(String channel) {}

    @PostMapping("/{id}/send")
    @RequiresPermission("invoices.manage")
    public ApiResponse<Invoice> send(@PathVariable Long id, @RequestBody SendRequest request) {
        return ResponseUtil.success("Invoice sent",
                invoiceService.send(id, request.channel() == null ? "EMAIL" : request.channel()));
    }

    @GetMapping("/{id}/pdf")
    @RequiresPermission("invoices.view")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Invoice invoice = invoiceService.get(id);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=" + invoice.getNumber() + ".pdf")
                .header("Content-Type", "application/pdf")
                .body(invoiceService.pdf(id));
    }
}
