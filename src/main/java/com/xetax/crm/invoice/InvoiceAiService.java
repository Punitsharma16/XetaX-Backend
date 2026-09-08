package com.xetax.crm.invoice;

import com.xetax.crm.billing.AiQuotaService;
import com.xetax.crm.common.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Draft with AI" for the invoice editor: one sentence in, structured line
 * items out. Deliberately an ISOLATED ChatClient — no tools, no memory, no
 * RAG — because this call must only ever shape the user's own words into
 * rows, never wander the CRM. Metered like any assistant message.
 */
@Service
@Slf4j
public class InvoiceAiService {

    private static final String SYSTEM = """
            You convert a business owner's description of an invoice into strict JSON.
            Reply with ONLY a JSON object, no prose, no markdown fences:
            {"items":[{"description":string,"quantity":number,"unitPrice":number}],
             "taxPercent":number,"discount":number,"notes":string,"customerName":string}
            Rules:
            - Currency is INR; "15k" means 15000, "1.5 lakh" means 150000.
            - Use the prices the user gave. If no price was given for an item, use 0.
            - quantity defaults to 1. taxPercent/discount default to 0 (GST mentioned => taxPercent 18 unless stated).
            - notes: one short professional payment-terms line if the user implied any, else "".
            - customerName: only if the user named the customer, else "".
            - Keep descriptions short and professional (max 8 words each).
            """;

    private final ChatClient chatClient;
    private final AiQuotaService quotaService;
    private final ObjectMapper mapper = new ObjectMapper();

    public InvoiceAiService(ChatClient.Builder builder, AiQuotaService quotaService) {
        this.chatClient = builder.build();
        this.quotaService = quotaService;
    }

    public Map<String, Object> draft(String ownerUserId, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new BadRequestException("Describe the invoice first — e.g. \"website 15k + 1 year hosting 5k, 18% GST\"");
        }
        quotaService.consumeAssistant(ownerUserId);

        String raw;
        try {
            raw = chatClient.prompt().system(SYSTEM).user(prompt.trim()).call().content();
        } catch (Exception e) {
            log.warn("Invoice AI draft failed: {}", e.getMessage());
            throw new BadRequestException("The AI is busy right now — try again in a minute");
        }

        try {
            String json = raw == null ? "" : raw.trim()
                    .replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
            JsonNode root = mapper.readTree(json);

            List<Map<String, Object>> items = new ArrayList<>();
            if (root.has("items")) {
                for (JsonNode item : root.get("items")) {
                    String description = item.path("description").asString("").trim();
                    if (description.isEmpty()) continue;
                    items.add(Map.of(
                            "description", description,
                            "quantity", Math.max(item.path("quantity").asDouble(1), 0.01),
                            "unitPrice", Math.max(item.path("unitPrice").asDouble(0), 0)));
                }
            }
            if (items.isEmpty()) {
                throw new BadRequestException("Couldn't read any items from that — add the amounts and try again");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", items);
            out.put("taxPercent", Math.min(Math.max(root.path("taxPercent").asDouble(0), 0), 100));
            out.put("discount", Math.max(root.path("discount").asDouble(0), 0));
            out.put("notes", root.path("notes").asString(""));
            out.put("customerName", root.path("customerName").asString(""));
            return out;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Invoice AI draft parse failed: {} — raw: {}", e.getMessage(), raw);
            throw new BadRequestException("The AI answer wasn't usable — rephrase and try again");
        }
    }
}
