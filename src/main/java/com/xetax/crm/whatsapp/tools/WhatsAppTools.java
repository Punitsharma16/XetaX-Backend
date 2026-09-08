package com.xetax.crm.whatsapp.tools;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.whatsapp.dto.CampaignCreateRequest;
import com.xetax.crm.whatsapp.dto.CampaignResponse;
import com.xetax.crm.whatsapp.dto.SendMessageRequest;
import com.xetax.crm.whatsapp.dto.WhatsAppConfigResponse;
import com.xetax.crm.whatsapp.dto.WhatsAppMessageResponse;
import com.xetax.crm.whatsapp.service.WhatsAppCampaignService;
import com.xetax.crm.whatsapp.service.WhatsAppConfigService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI tools for the WhatsApp module.
 *
 * <p>Security: every underlying service call reads the SecurityContext user —
 * userId is never a tool parameter, tokens are never in any result, and
 * another user's data behaves as if it does not exist. Sending is allowed
 * ONLY when the user explicitly asked to send; campaigns are created as
 * drafts and are NEVER started by the AI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppTools {

    private final WhatsAppConfigService configService;
    private final WhatsAppMessagingService messagingService;
    private final WhatsAppTemplateService templateService;
    private final WhatsAppCampaignService campaignService;

    @Tool(description = "Get the current user's WhatsApp connection status "
            + "(connected phone number, verified name, quality rating).")
    @RequiresPermission("whatsapp.view")
    public Map<String, Object> getWhatsAppStatus() {
        try {
            WhatsAppConfigResponse status = configService.statusResponse();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status.getStatus());
            out.put("phoneNumber", status.getDisplayPhoneNumber());
            out.put("businessName", status.getVerifiedName());
            out.put("qualityRating", status.getQualityRating());
            return out;
        } catch (Exception e) {
            return Map.of("error", "Could not read the WhatsApp status right now.");
        }
    }

    @Tool(description = "List the user's approved WhatsApp message templates "
            + "(name, language, category, status). Requires WhatsApp to be connected.")
    @RequiresPermission("whatsapp.view")
    public List<Map<String, Object>> getWhatsAppTemplates() {
        try {
            return templateService.myTemplates().stream()
                    .map(t -> {
                        Map<String, Object> map = new LinkedHashMap<String, Object>();
                        map.put("name", t.getName());
                        map.put("language", t.getLanguage());
                        map.put("category", t.getCategory());
                        map.put("status", t.getStatus());
                        return map;
                    })
                    .toList();
        } catch (Exception e) {
            return List.of(Map.of("error", "WhatsApp is not connected or templates are unavailable."));
        }
    }

    @Tool(description = "List the user's WhatsApp campaigns with progress counters.")
    @RequiresPermission("whatsapp.campaigns")
    public List<Map<String, Object>> getWhatsAppCampaigns() {
        try {
            return campaignService.list(0, 25).getContent().stream()
                    .map(WhatsAppTools::campaignMap)
                    .toList();
        } catch (Exception e) {
            return List.of(Map.of("error", "Could not read campaigns right now."));
        }
    }

    @Tool(description = "Get one WhatsApp campaign's details and delivery progress by its id.")
    @RequiresPermission("whatsapp.campaigns")
    public Map<String, Object> getWhatsAppCampaignDetails(
            @ToolParam(description = "Campaign id from getWhatsAppCampaigns") Long campaignId) {
        try {
            return campaignMap(campaignService.get(campaignId));
        } catch (Exception e) {
            return Map.of("error", "No campaign with this id exists for the current user.");
        }
    }

    @Tool(description = "Send ONE WhatsApp text message to a phone number. Use ONLY when the "
            + "user explicitly asked to send a WhatsApp message right now — never send "
            + "proactively. Confirm the phone number and exact text with the user first.")
    @RequiresPermission("whatsapp.inbox")
    public Map<String, Object> sendWhatsAppMessage(
            @ToolParam(description = "Recipient phone number with country code, e.g. 919876543210") String phone,
            @ToolParam(description = "Exact message text the user approved") String message) {
        try {
            SendMessageRequest request = new SendMessageRequest();
            request.setPhone(phone);
            request.setMessage(message);
            WhatsAppMessageResponse sent = messagingService.send(request);
            return Map.of("queued", true, "status", sent.getStatus(), "to", sent.getToPhone());
        } catch (Exception e) {
            return Map.of("queued", false, "error", safeMessage(e));
        }
    }

    @Tool(description = "Create a DRAFT WhatsApp campaign targeting records of one of the user's "
            + "forms. The draft is NOT sent — the user must review and start it from the "
            + "Campaigns page. Get the form's slug via getMyForms and the phone field's "
            + "fieldKey via getFormFields first. The message may use {fieldKey} placeholders.")
    @RequiresPermission("whatsapp.campaigns")
    public Map<String, Object> createWhatsAppCampaignDraft(
            @ToolParam(description = "Campaign name") String name,
            @ToolParam(description = "Slug of the user's form (from getMyForms)") String formSlug,
            @ToolParam(description = "fieldKey of the field holding the phone number") String phoneFieldKey,
            @ToolParam(description = "Message text, may contain {fieldKey} placeholders") String message) {
        try {
            CampaignCreateRequest request = new CampaignCreateRequest();
            request.setName(name);
            request.setSourceType("RECORDS");
            request.setFormSlug(formSlug);
            request.setPhoneFieldKey(phoneFieldKey);
            request.setMessageTemplate(message);
            CampaignResponse created = campaignService.create(request);
            Map<String, Object> out = campaignMap(created);
            out.put("note", "Draft created. Ask the user to review and start it from the WhatsApp → Campaigns page.");
            return out;
        } catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    private static Map<String, Object> campaignMap(CampaignResponse campaign) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", campaign.getId());
        map.put("name", campaign.getName());
        map.put("status", campaign.getStatus());
        map.put("audience", campaign.getTargetDescription());
        map.put("total", campaign.getTotalCount());
        map.put("sent", campaign.getSentCount());
        map.put("delivered", campaign.getDeliveredCount());
        map.put("read", campaign.getReadCount());
        map.put("failed", campaign.getFailedCount());
        return map;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? "The WhatsApp action could not be completed." : message;
    }
}
