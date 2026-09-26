package com.xetax.crm.document.tools;

import com.xetax.crm.document.DocumentFile;
import com.xetax.crm.document.DocumentService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI tools for the document library.
 *
 * <p>Uploading is a file picker, so it stays on the page. What the assistant
 * is useful for is the other half: finding the right brochure and getting it
 * to a customer without leaving the chat.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentTools {

    private final DocumentService documentService;

    @Tool(description = """
            READ-ONLY. List the user's uploaded documents — brochures, price
            lists, agreements — with their names and whether they support
            {placeholder} variables that get filled from a record.
            """)
    @RequiresPermission("documents.view")
    public Map<String, Object> getMyDocuments() {
        try {
            List<DocumentFile> documents = documentService.list();
            return Map.of("count", documents.size(),
                    "documents", documents.stream().map(DocumentTools::documentMap).toList());
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Send one document to one person. channel is WHATSAPP (pass
            phone) or EMAIL (pass "to"). Use ONLY when the user explicitly
            asks to send it — this reaches a customer.

            Prefer attaching it to who it is for: pass recordId (from
            getRecords/searchRecords) or contactId (from searchMyContacts)
            and the phone/email is taken from there. Set personalize to true
            for a document with {placeholder} variables so they are filled
            from that record.
            """)
    @RequiresPermission("documents.manage")
    public Map<String, Object> sendDocument(
            @ToolParam(description = "Document id from getMyDocuments") Long documentId,
            @ToolParam(description = "WHATSAPP or EMAIL") String channel,
            @ToolParam(description = "Phone with country code, for WHATSAPP", required = false)
            String phone,
            @ToolParam(description = "Email address, for EMAIL", required = false) String to,
            @ToolParam(description = "Email subject", required = false) String subject,
            @ToolParam(description = "Covering message", required = false) String message,
            @ToolParam(description = "Record id this document is for", required = false)
            String recordId,
            @ToolParam(description = "Contact id this document is for", required = false)
            Long contactId,
            @ToolParam(description = "Fill {placeholder} variables from the record",
                    required = false) Boolean personalize) {
        try {
            documentService.send(documentId, new DocumentService.SendRequest(
                    channel, blankToNull(phone), blankToNull(to), blankToNull(subject),
                    blankToNull(message), blankToNull(recordId), contactId,
                    Boolean.TRUE.equals(personalize)));
            return Map.of("sent", true, "documentId", documentId, "channel", String.valueOf(channel));
        }
        catch (Exception e) {
            return Map.of("sent", false, "error", safeMessage(e));
        }
    }

    private static Map<String, Object> documentMap(DocumentFile document) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", document.getId());
        map.put("name", document.getName());
        map.put("type", document.getContentType());
        map.put("supportsVariables", document.isSupportsVariables());
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? "The document action could not be completed." : message;
    }
}
