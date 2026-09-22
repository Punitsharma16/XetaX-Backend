package com.xetax.crm.ai.tools;

import com.xetax.crm.contact.Contact;
import com.xetax.crm.contact.ContactService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI READ tools for the address book, so the assistant can answer
 * questions about contacts the same way it already answers them about forms
 * and records.
 *
 * <p>Read-only on purpose: writing to a contact means messaging a real person,
 * which stays with the Contacts page. Ownership comes from the signed-in
 * session inside {@link ContactService} — a user id is never a tool argument.
 */
@Slf4j
@Component
public class ContactTools {

    private static final int MAX_PAGE = 50;

    private final ContactService contactService;

    public ContactTools(ContactService contactService) {
        this.contactService = contactService;
    }

    @Tool(description = """
            READ. List the signed-in CRM user's saved contacts (the address
            book), newest page first, ordered by name. Arguments: page —
            0-based page number, default 0; size — contacts per page, default
            20, maximum 50. Returns total (how many contacts exist), page,
            size and contacts (id, name, phone, email, company, address).
            Use this for questions like "how many contacts do I have" or
            "list my contacts".
            """)
    @RequiresPermission("contacts.view")
    public Map<String, Object> getMyContacts(
            @ToolParam(required = false, description = "0-based page number, default 0") Integer page,
            @ToolParam(required = false, description = "Contacts per page, default 20, max 50") Integer size) {
        return page(null, page, size);
    }

    @Tool(description = """
            READ. Search the signed-in CRM user's contacts by name, phone,
            email or company. Arguments: searchText — the words to look for
            (required); page — 0-based page number, default 0; size —
            contacts per page, default 20, maximum 50. Returns the same shape
            as getMyContacts. Use this whenever the user names a person, a
            number or a business instead of asking for the whole list.
            """)
    @RequiresPermission("contacts.view")
    public Map<String, Object> searchMyContacts(
            @ToolParam(description = "Words to look for in name, phone, email or company") String searchText,
            @ToolParam(required = false, description = "0-based page number, default 0") Integer page,
            @ToolParam(required = false, description = "Contacts per page, default 20, max 50") Integer size) {
        if (searchText == null || searchText.isBlank()) {
            return Map.of("error", "searchText is required — say what to look for");
        }
        return page(searchText, page, size);
    }

    private Map<String, Object> page(String query, Integer page, Integer size) {
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? 20 : Math.min(size, MAX_PAGE);

        var found = contactService.list(query, pageNumber, pageSize);
        List<Map<String, Object>> contacts = found.getContent().stream().map(ContactTools::describe).toList();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("total", found.getTotalElements());
        answer.put("page", pageNumber);
        answer.put("size", pageSize);
        answer.put("contacts", contacts);
        return answer;
    }

    private static Map<String, Object> describe(Contact contact) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", contact.getId());
        row.put("name", contact.getName());
        row.put("phone", contact.getPhone());
        row.put("email", contact.getEmail());
        row.put("company", contact.getCompany());
        row.put("address", contact.getAddress());
        return row;
    }
}
