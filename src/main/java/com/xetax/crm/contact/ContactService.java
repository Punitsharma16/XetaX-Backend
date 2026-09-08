package com.xetax.crm.contact;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Address book + one-click / bulk outreach. Sends reuse the org's own
 * channels: WhatsApp goes through the messaging service (24h-window rules
 * apply), email through OrgSmtpService (which also writes the history log).
 * {{name}} in any message/body is replaced per contact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContactService {

    private final ContactRepository contactRepository;
    private final EmailLogRepository emailLogRepository;
    private final CurrentUserProvider currentUserProvider;
    private final OrgSmtpService orgSmtpService;
    private final WhatsAppMessagingService whatsAppMessagingService;

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    // ----------------------------------------------------------------- crud

    public Page<Contact> list(String query, int page, int size) {
        String own = owner();
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return (query == null || query.isBlank())
                ? contactRepository.findByOwnerUserIdOrderByNameAsc(own, pr)
                : contactRepository.search(own, query.trim(), pr);
    }

    public Contact create(Contact input) {
        validate(input);
        input.setId(null);
        input.setOwnerUserId(owner());
        input.setCreatedAt(LocalDateTime.now());
        input.setUpdatedAt(LocalDateTime.now());
        return contactRepository.save(input);
    }

    public Contact update(Long id, Contact input) {
        validate(input);
        Contact contact = find(id);
        contact.setName(input.getName().trim());
        contact.setPhone(blankToNull(input.getPhone()));
        contact.setEmail(blankToNull(input.getEmail()));
        contact.setCompany(blankToNull(input.getCompany()));
        contact.setAddress(blankToNull(input.getAddress()));
        contact.setNotes(blankToNull(input.getNotes()));
        contact.setUpdatedAt(LocalDateTime.now());
        return contactRepository.save(contact);
    }

    // ------------------------------------------------------ import / export

    public record ImportResult(int imported, int skipped, int failed, List<String> errors) {}

    /**
     * CSV import. Header row is matched case-insensitively against
     * name/phone/email/company/address/notes (a few common aliases accepted).
     * A row whose phone or email already exists for this owner is skipped as a
     * duplicate; a bad row is reported with its number and never stops the rest.
     */
    public ImportResult importCsv(org.springframework.web.multipart.MultipartFile file) {
        String own = owner();
        List<List<String>> rows;
        try {
            rows = com.xetax.crm.common.util.CsvParser.parse(file.getInputStream());
        } catch (Exception e) {
            throw new BadRequestException("Could not read the CSV file");
        }
        if (rows.size() < 2) throw new BadRequestException("The file must have a header row and at least one data row");
        if (rows.size() - 1 > 2000) throw new BadRequestException("Maximum 2000 contacts per file");

        // header -> column index
        Map<String, Integer> col = new LinkedHashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            String key = switch (header.get(i).trim().toLowerCase()) {
                case "name", "full name", "contact name" -> "name";
                case "phone", "mobile", "phone number", "mobile number" -> "phone";
                case "email", "email id", "e-mail" -> "email";
                case "company", "company name", "business" -> "company";
                case "address" -> "address";
                case "notes", "note", "remarks" -> "notes";
                default -> null;
            };
            if (key != null) col.putIfAbsent(key, i);
        }
        if (!col.containsKey("name")) throw new BadRequestException("The header row must have a 'name' column");

        int imported = 0, skipped = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String name = cell(row, col.get("name"));
            String phone = cell(row, col.get("phone"));
            String email = cell(row, col.get("email"));
            try {
                if (name == null) throw new BadRequestException("name is required");
                if (phone == null && email == null) throw new BadRequestException("phone or email is required");
                boolean duplicate = (phone != null && contactRepository.existsByOwnerUserIdAndPhone(own, phone))
                        || (email != null && contactRepository.existsByOwnerUserIdAndEmailIgnoreCase(own, email));
                if (duplicate) { skipped++; continue; }

                Contact contact = new Contact();
                contact.setName(name);
                contact.setPhone(phone);
                contact.setEmail(email);
                contact.setCompany(cell(row, col.get("company")));
                contact.setAddress(cell(row, col.get("address")));
                contact.setNotes(cell(row, col.get("notes")));
                create(contact);
                imported++;
            } catch (Exception e) {
                failed++;
                if (errors.size() < 50) errors.add("Row " + (r + 1) + ": " + e.getMessage());
            }
        }
        return new ImportResult(imported, skipped, failed, errors);
    }

    private String cell(List<String> row, Integer index) {
        if (index == null || index >= row.size()) return null;
        String v = row.get(index);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** All of the owner's contacts as a CSV, same columns the import accepts. */
    public String exportCsv() {
        StringBuilder sb = new StringBuilder("name,phone,email,company,address,notes\n");
        for (Contact c : contactRepository.findAllByOwnerUserIdOrderByNameAsc(owner())) {
            sb.append(csv(c.getName())).append(',').append(csv(c.getPhone())).append(',')
              .append(csv(c.getEmail())).append(',').append(csv(c.getCompany())).append(',')
              .append(csv(c.getAddress())).append(',').append(csv(c.getNotes())).append('\n');
        }
        return sb.toString();
    }

    private String csv(String v) {
        if (v == null) return "";
        return v.contains(",") || v.contains("\"") || v.contains("\n")
                ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    /** Existing contacts sharing this phone/email — the duplicate warning's data. */
    public List<Map<String, Object>> duplicates(String phone, String email, Long excludeId) {
        String own = owner();
        Map<Long, Contact> hits = new LinkedHashMap<>();
        if (phone != null && !phone.isBlank()) {
            contactRepository.findTop5ByOwnerUserIdAndPhone(own, phone.trim())
                    .forEach(c -> hits.put(c.getId(), c));
        }
        if (email != null && !email.isBlank()) {
            contactRepository.findTop5ByOwnerUserIdAndEmailIgnoreCase(own, email.trim())
                    .forEach(c -> hits.put(c.getId(), c));
        }
        if (excludeId != null) hits.remove(excludeId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Contact c : hits.values()) {
            if (out.size() >= 5) break;
            out.add(Map.of("id", c.getId(), "name", c.getName(),
                    "phone", c.getPhone() == null ? "" : c.getPhone(),
                    "email", c.getEmail() == null ? "" : c.getEmail()));
        }
        return out;
    }

    public Contact get(Long id) {
        return find(id);
    }

    public void delete(Long id) {
        contactRepository.delete(find(id));
    }

    private Contact find(Long id) {
        return contactRepository.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Contact not found"));
    }

    private void validate(Contact c) {
        if (c.getName() == null || c.getName().isBlank()) {
            throw new BadRequestException("Name is required");
        }
        boolean noPhone = c.getPhone() == null || c.getPhone().isBlank();
        boolean noEmail = c.getEmail() == null || c.getEmail().isBlank();
        if (noPhone && noEmail) {
            throw new BadRequestException("Add at least a phone number or an email");
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ---------------------------------------------------------------- email

    public List<EmailLog> emailHistory(Long id) {
        Contact contact = find(id);
        if (contact.getEmail() == null) return List.of();
        return emailLogRepository
                .findTop50ByOwnerUserIdAndToEmailIgnoreCaseOrderByIdDesc(owner(), contact.getEmail());
    }

    public void sendEmail(Long id, String subject, String body) {
        Contact contact = find(id);
        requireEmailReady(contact);
        try {
            orgSmtpService.sendAs(owner(), contact.getEmail(),
                    personalize(subject, contact), personalize(body, contact));
        } catch (org.springframework.mail.MailException e) {
            throw new BadRequestException(
                    "Email failed to send — check your SMTP settings (Profile → Email). "
                    + "A FAILED entry was added to the history.");
        }
    }

    public Map<String, Object> bulkEmail(List<Long> ids, String subject, String body) {
        requireText(subject, "Subject");
        requireText(body, "Message");
        if (!orgSmtpService.isConfiguredFor(owner())) {
            throw new BadRequestException(
                    "Email is not configured yet — add your account under Profile → Email (SMTP).");
        }
        int sent = 0;
        List<String> failed = new ArrayList<>();
        for (Contact contact : contactRepository.findByIdInAndOwnerUserId(ids, owner())) {
            if (contact.getEmail() == null || contact.getEmail().isBlank()) {
                failed.add(contact.getName() + " (no email saved)");
                continue;
            }
            try {
                orgSmtpService.sendAs(owner(), contact.getEmail(),
                        personalize(subject, contact), personalize(body, contact));
                sent++;
            } catch (Exception e) {
                failed.add(contact.getName());
                log.warn("Bulk email to {} failed: {}", contact.getEmail(), e.getMessage());
            }
        }
        return result(sent, failed);
    }

    private void requireEmailReady(Contact contact) {
        if (contact.getEmail() == null || contact.getEmail().isBlank()) {
            throw new BadRequestException("This contact has no email address saved.");
        }
        if (!orgSmtpService.isConfiguredFor(owner())) {
            throw new BadRequestException(
                    "Email is not configured yet — add your account under Profile → Email (SMTP).");
        }
    }

    // ------------------------------------------------------------- whatsapp

    public void sendWhatsApp(Long id, String message) {
        requireText(message, "Message");
        Contact contact = find(id);
        if (contact.getPhone() == null || contact.getPhone().isBlank()) {
            throw new BadRequestException("This contact has no phone number saved.");
        }
        try {
            whatsAppMessagingService.sendTextAsOwner(owner(), contact.getPhone(),
                    personalize(message, contact));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    /** Text (24h window) ya approved template — template ho to window nahi lagti. */
    public Map<String, Object> bulkWhatsApp(List<Long> ids, String message,
                                            String templateName, String templateLanguage) {
        boolean isTemplate = templateName != null && !templateName.isBlank();
        if (!isTemplate) requireText(message, "Message");
        int sent = 0;
        List<String> failed = new ArrayList<>();
        for (Contact contact : contactRepository.findByIdInAndOwnerUserId(ids, owner())) {
            if (contact.getPhone() == null || contact.getPhone().isBlank()) {
                failed.add(contact.getName() + " (no phone saved)");
                continue;
            }
            try {
                if (isTemplate) {
                    com.xetax.crm.whatsapp.dto.SendMessageRequest request =
                            new com.xetax.crm.whatsapp.dto.SendMessageRequest();
                    request.setPhone(contact.getPhone());
                    request.setTemplateName(templateName);
                    request.setTemplateLanguage(templateLanguage);
                    whatsAppMessagingService.send(request);
                } else {
                    whatsAppMessagingService.sendTextAsOwner(owner(), contact.getPhone(),
                            personalize(message, contact));
                }
                sent++;
            } catch (Exception e) {
                failed.add(contact.getName());
                log.warn("Bulk WhatsApp to {} failed: {}", contact.getPhone(), e.getMessage());
            }
        }
        return result(sent, failed);
    }

    // -------------------------------------------------------------- helpers

    private void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new BadRequestException(label + " is required");
    }

    private String personalize(String text, Contact contact) {
        return text == null ? null : text.replace("{{name}}", contact.getName());
    }

    private Map<String, Object> result(int sent, List<String> failed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", sent);
        out.put("failed", failed.size());
        out.put("failedNames", failed);
        return out;
    }
}
