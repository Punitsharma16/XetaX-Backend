package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.entity.WhatsAppFlow;
import com.xetax.crm.whatsapp.entity.WhatsAppFlowResponse;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.entity.WhatsAppTemplate;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppFlowResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Gives every Flow button on a template its own flow_token.
 *
 * <p>Outside the 24-hour window a Flow can only reach a customer as a button
 * on an approved template. Meta sends back whatever flow_token the send
 * carried, and when a send carries none it sends back the literal "unused" —
 * so every customer's answers arrived under the same token, none of them
 * could be traced to a Flow, and no CRM record was ever created. A token is
 * minted here for each Flow button, with a waiting row saved first, exactly as
 * a Flow sent on its own inside the window.
 *
 * <p>Applied in the one place every template leaves from, so single sends,
 * campaigns, the record page, contacts and the playbook all get it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateFlowTokens {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WhatsAppFlowRepository flowRepository;
    private final WhatsAppFlowResponseRepository responseRepository;

    /** The components to send, and the waiting rows created for them. */
    public record Attached(String componentsJson, List<Long> pendingIds) {}

    public Attached attach(WhatsAppTemplate template, WhatsAppMessage message, String componentsJson) {
        if (template == null || message == null) return new Attached(componentsJson, List.of());
        List<FlowButton> buttons = flowButtons(template.getComponentsJson());
        if (buttons.isEmpty()) return new Attached(componentsJson, List.of());

        try {
            List<Map<String, Object>> components = componentsJson == null || componentsJson.isBlank()
                    ? new ArrayList<>()
                    : new ArrayList<>(MAPPER.readValue(componentsJson, new TypeReference<List<Map<String, Object>>>() {}));

            // A caller that already set a button's parameters keeps them.
            Set<String> supplied = new HashSet<>();
            for (Map<String, Object> component : components) {
                if ("button".equalsIgnoreCase(String.valueOf(component.get("type")))) {
                    supplied.add(String.valueOf(component.get("index")));
                }
            }

            List<Long> pending = new ArrayList<>();
            for (FlowButton button : buttons) {
                String index = String.valueOf(button.index());
                if (supplied.contains(index)) continue;

                WhatsAppFlow flow = flowRepository.findByMetaFlowId(button.metaFlowId())
                        .filter(f -> Objects.equals(f.getOwnerUserId(), message.getOwnerUserId()))
                        .orElse(null);
                if (flow == null) {
                    // A Flow built outside XetaX: nothing here can record its answers.
                    log.debug("Template {} button {} opens Flow {} which is not one of ours",
                            template.getName(), index, button.metaFlowId());
                    continue;
                }

                String token = "flw_" + flow.getId() + "_" + UUID.randomUUID().toString().replace("-", "");
                WhatsAppFlowResponse row = responseRepository.save(WhatsAppFlowResponse.builder()
                        .ownerUserId(flow.getOwnerUserId())
                        .flowId(flow.getId())
                        .flowToken(token)
                        .customerPhone(message.getToPhone())
                        .conversationId(message.getConversationId())
                        .recordId(message.getRecordId())
                        .note("Sent in template '" + template.getName() + "' — waiting for the customer to submit")
                        .build());
                if (row.getId() != null) pending.add(row.getId());

                Map<String, Object> component = new LinkedHashMap<>();
                component.put("type", "button");
                component.put("sub_type", "flow");
                component.put("index", index);
                component.put("parameters", List.of(Map.of(
                        "type", "action",
                        "action", Map.of("flow_token", token))));
                components.add(component);
            }
            if (pending.isEmpty()) return new Attached(componentsJson, List.of());
            return new Attached(MAPPER.writeValueAsString(components), pending);
        } catch (Exception e) {
            log.warn("Could not attach Flow tokens for template {}: {}", template.getName(), e.getMessage());
            return new Attached(componentsJson, List.of());
        }
    }

    /** The send failed, so nobody is going to fill these in — say so rather than "waiting". */
    public void markUndelivered(List<Long> pendingIds, String reason) {
        if (pendingIds == null || pendingIds.isEmpty()) return;
        String note = "Not delivered" + (reason == null || reason.isBlank() ? "" : " — " + reason);
        List<WhatsAppFlowResponse> rows = responseRepository.findAllById(pendingIds);
        for (WhatsAppFlowResponse row : rows) {
            row.setNote(note.length() > 250 ? note.substring(0, 250) : note);
        }
        responseRepository.saveAll(rows);
    }

    record FlowButton(int index, String metaFlowId) {}

    /** The template's top-level FLOW buttons, with their position and Meta Flow id. */
    static List<FlowButton> flowButtons(String componentsJson) {
        if (componentsJson == null || componentsJson.isBlank()) return List.of();
        try {
            List<FlowButton> out = new ArrayList<>();
            for (JsonNode component : MAPPER.readTree(componentsJson)) {
                if (!"BUTTONS".equalsIgnoreCase(component.path("type").asText())) continue;
                int index = 0;
                for (JsonNode button : component.path("buttons")) {
                    if ("FLOW".equalsIgnoreCase(button.path("type").asText())) {
                        String flowId = button.path("flow_id").asText("");
                        if (!flowId.isBlank()) out.add(new FlowButton(index, flowId));
                    }
                    index++;
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
