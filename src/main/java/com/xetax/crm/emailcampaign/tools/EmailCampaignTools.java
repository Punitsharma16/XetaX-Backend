package com.xetax.crm.emailcampaign.tools;

import com.xetax.crm.emailcampaign.dto.EmailCampaignCreateRequest;
import com.xetax.crm.emailcampaign.dto.EmailCampaignResponse;
import com.xetax.crm.emailcampaign.service.EmailCampaignService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Spring AI tools for bulk email.
 *
 * <p>Mirrors the WhatsApp campaign rules exactly, and for the same reason: a
 * campaign reaches thousands of the user's customers from their own mailbox,
 * so the assistant may draft one and may stop one, but never starts one. That
 * click stays with the person whose sender reputation is on the line.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailCampaignTools {

    private final EmailCampaignService campaignService;

    @Tool(description = """
            READ-ONLY. Whether bulk email is usable: the org's own SMTP
            sender, whether it is verified, and the sending limits. Check this
            before drafting a campaign so the user is not promised something
            that cannot go out.
            """)
    @RequiresPermission("email.campaigns")
    public Map<String, Object> getEmailStatus() {
        try {
            return campaignService.status();
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            READ-ONLY. List the user's email campaigns with their status and
            counts (queued, sent, failed). Argument page is 0-based.
            """)
    @RequiresPermission("email.campaigns")
    public Map<String, Object> getEmailCampaigns(
            @ToolParam(description = "0-based page", required = false) Integer page) {
        try {
            var found = campaignService.list(page == null ? 0 : page, 10);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", found.getTotalElements());
            out.put("campaigns", found.getContent().stream()
                    .map(EmailCampaignTools::campaignMap).toList());
            return out;
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            READ-ONLY. One email campaign in full, including its subject, body
            and how many messages have gone out. Argument campaignId comes
            from getEmailCampaigns.
            """)
    @RequiresPermission("email.campaigns")
    public Map<String, Object> getEmailCampaignDetails(
            @ToolParam(description = "Campaign id from getEmailCampaigns") Long campaignId) {
        try {
            Map<String, Object> out =
                    new LinkedHashMap<>(campaignMap(campaignService.get(campaignId)));
            out.put("body", campaignService.get(campaignId).getBody());
            return out;
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Create an email campaign as a DRAFT. Use ONLY when the user
            explicitly asks for one, and show them the subject and body first.

            The audience comes from one of the user's forms: pass formSlug
            (from findMyFormByName/getMyForms) and emailFieldKey, the key of
            the field that holds the email address (from getFormFields).
            search optionally narrows the records.

            NEVER start the campaign — the user starts it from the Email
            Campaigns page after reviewing the recipients. Placeholders like
            {name} in the body are filled from each record.
            """)
    @RequiresPermission("email.campaigns")
    public Map<String, Object> createEmailCampaignDraft(
            @ToolParam(description = "Campaign name, for the user's own list") String name,
            @ToolParam(description = "Subject line") String subject,
            @ToolParam(description = "Message body; {fieldKey} placeholders allowed") String body,
            @ToolParam(description = "Form slug whose records are the audience") String formSlug,
            @ToolParam(description = "Field key holding the email address") String emailFieldKey,
            @ToolParam(description = "Optional text to narrow the records", required = false)
            String search) {
        try {
            EmailCampaignCreateRequest request = new EmailCampaignCreateRequest();
            request.setName(name);
            request.setSubject(subject);
            request.setBody(body);
            request.setSourceType("FORM");
            request.setFormSlug(formSlug);
            request.setEmailFieldKey(emailFieldKey);
            request.setSearch(search == null || search.isBlank() ? null : search.trim());

            Map<String, Object> out =
                    new LinkedHashMap<>(campaignMap(campaignService.create(request)));
            out.put("created", true);
            out.put("note", "Saved as a draft. Review the recipients and start it from the "
                    + "Email Campaigns page — I do not start campaigns.");
            return out;
        }
        catch (Exception e) {
            return Map.of("created", false, "error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Stop or resume a campaign that is already running. action is
            PAUSE, RESUME or CANCEL. Use ONLY on an explicit request. There is
            deliberately no way to START a campaign from here.
            """)
    @RequiresPermission("email.campaigns")
    public Map<String, Object> setEmailCampaignState(
            @ToolParam(description = "Campaign id from getEmailCampaigns") Long campaignId,
            @ToolParam(description = "PAUSE, RESUME or CANCEL") String action) {
        try {
            String wanted = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
            EmailCampaignResponse updated = switch (wanted) {
                case "PAUSE" -> campaignService.pause(campaignId);
                case "RESUME" -> campaignService.resume(campaignId);
                case "CANCEL" -> campaignService.cancel(campaignId);
                default -> null;
            };
            if (updated == null) {
                return Map.of("updated", false,
                        "error", "action must be PAUSE, RESUME or CANCEL.");
            }
            Map<String, Object> out = new LinkedHashMap<>(campaignMap(updated));
            out.put("updated", true);
            return out;
        }
        catch (Exception e) {
            return Map.of("updated", false, "error", safeMessage(e));
        }
    }

    private static Map<String, Object> campaignMap(EmailCampaignResponse campaign) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", campaign.getId());
        map.put("name", campaign.getName());
        map.put("subject", campaign.getSubject());
        map.put("status", campaign.getStatus());
        map.put("audience", campaign.getTargetDescription());
        map.put("total", campaign.getTotalCount());
        map.put("sent", campaign.getSentCount());
        map.put("failed", campaign.getFailedCount());
        map.put("queued", campaign.getQueuedCount());
        return map;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? "The email campaign action could not be completed." : message;
    }
}
