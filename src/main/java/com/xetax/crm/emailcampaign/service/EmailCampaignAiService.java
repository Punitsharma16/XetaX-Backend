package com.xetax.crm.emailcampaign.service;

import com.xetax.crm.billing.AiQuotaService;
import com.xetax.crm.common.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Draft with AI" for an email campaign: one sentence about the offer in, a
 * subject line and a message out. An ISOLATED ChatClient like the invoice
 * drafter — no tools, no memory, no knowledge base — because this only has to
 * shape the sender's own words, never read the CRM. Metered like any
 * assistant message.
 */
@Service
@Slf4j
public class EmailCampaignAiService {

    private static final String SYSTEM = """
            You write one marketing or service email for a small Indian business.
            Reply with ONLY a JSON object, no prose, no markdown fences:
            {"subject":string,"body":string}
            Rules:
            - English, warm and plain. No hype, no emoji spam, at most one emoji.
            - subject: under 60 characters, says what the email is about.
            - body: 60-120 words, greeting, the offer or news, one clear next step.
            - Use ONLY the placeholders the user lists, written as {key} (for example
              {name}). Never invent a placeholder, and never leave a blank to fill in.
            - If the user gives no placeholder, address the reader directly instead.
            - No subject line inside the body, no signature block, no "Dear Sir/Madam".
            """;

    private final ChatClient chatClient;
    private final AiQuotaService quotaService;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmailCampaignAiService(ChatClient.Builder builder, AiQuotaService quotaService) {
        this.chatClient = builder.build();
        this.quotaService = quotaService;
    }

    public Map<String, Object> draft(String ownerUserId, String prompt, List<String> placeholders) {
        if (prompt == null || prompt.isBlank()) {
            throw new BadRequestException(
                    "Describe the email first — e.g. \"Diwali offer, 20% off on every service till 5 Nov\"");
        }
        quotaService.consumeAssistant(ownerUserId);

        String allowed = placeholders == null || placeholders.isEmpty()
                ? "none"
                : String.join(", ", placeholders.stream().map(key -> "{" + key + "}").toList());

        String raw;
        try {
            raw = chatClient.prompt()
                    .system(SYSTEM)
                    .user("Placeholders available: " + allowed + "\n\nEmail to write:\n" + prompt.trim())
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("Email campaign AI draft failed: {}", e.getMessage());
            throw new BadRequestException("The AI is busy right now — try again in a minute");
        }

        try {
            String json = raw == null ? "" : raw.trim()
                    .replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
            JsonNode root = mapper.readTree(json);

            String subject = root.path("subject").asString("").trim();
            String body = root.path("body").asString("").trim();
            if (subject.isEmpty() || body.isEmpty()) {
                throw new BadRequestException("The AI answer wasn't usable — rephrase and try again");
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("subject", subject);
            out.put("body", body);
            return out;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Email campaign AI draft parse failed: {} — raw: {}", e.getMessage(), raw);
            throw new BadRequestException("The AI answer wasn't usable — rephrase and try again");
        }
    }
}
