package com.xetax.crm.desk;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Live Chat Desk — the floating button in the panel. */
@RestController
@RequestMapping("/api/desk")
@RequiredArgsConstructor
public class DeskController {

    private final DeskService desk;

    @GetMapping("/badge")
    public ApiResponse<Map<String, Object>> badge() {
        return ResponseUtil.success("Desk badge", desk.badge());
    }

    @GetMapping("/state")
    @RequiresPermission("desk.handle")
    public ApiResponse<Map<String, Object>> state() {
        return ResponseUtil.success("Desk", desk.state());
    }

    @PostMapping("/requests/{id}/accept")
    @RequiresPermission("desk.handle")
    public ApiResponse<Map<String, Object>> accept(@PathVariable Long id) {
        return ResponseUtil.success("You're in the chat", desk.accept(id));
    }

    @PostMapping("/requests/{id}/decline")
    @RequiresPermission("desk.handle")
    public ApiResponse<Void> decline(@PathVariable Long id) {
        desk.decline(id);
        return ResponseUtil.success("Handed back to the assistant", null);
    }

    @GetMapping("/sessions/{id}/messages")
    @RequiresPermission("desk.handle")
    public ApiResponse<Map<String, Object>> messages(@PathVariable Long id,
                                                     @RequestParam(required = false) Long after) {
        return ResponseUtil.success("Messages", desk.messages(id, after));
    }

    public record ReplyRequest(String text) {}

    @PostMapping("/sessions/{id}/messages")
    @RequiresPermission("desk.handle")
    public ApiResponse<Map<String, Object>> reply(@PathVariable Long id, @RequestBody ReplyRequest body) {
        return ResponseUtil.success("Sent", desk.reply(id, body.text()));
    }

    public record ResolveRequest(Boolean resumeAi) {}

    @PostMapping("/sessions/{id}/resolve")
    @RequiresPermission("desk.handle")
    public ApiResponse<Void> resolve(@PathVariable Long id, @RequestBody(required = false) ResolveRequest body) {
        desk.resolve(id, body != null && Boolean.TRUE.equals(body.resumeAi()));
        return ResponseUtil.success("Resolved", null);
    }

    public record AssignRequest(String userId) {}

    @PostMapping("/sessions/{id}/assign")
    @RequiresPermission("desk.handle")
    public ApiResponse<Void> assign(@PathVariable Long id, @RequestBody AssignRequest body) {
        desk.assign(id, body.userId());
        return ResponseUtil.success("Assigned", null);
    }

    /* ---- record page: AI summary + pending suggestion (records.view is enforced by the record page itself) */

    @GetMapping("/records/{recordId}/ai")
    public ApiResponse<Map<String, Object>> recordAi(@PathVariable String recordId) {
        return ResponseUtil.success("AI conversation", desk.recordAi(recordId));
    }

    @PostMapping("/sessions/{id}/apply-suggestion")
    public ApiResponse<Map<String, Object>> applySuggestion(@PathVariable Long id) {
        return ResponseUtil.success("Applied", desk.applySuggestion(id));
    }

    @PostMapping("/sessions/{id}/dismiss-suggestion")
    public ApiResponse<Void> dismissSuggestion(@PathVariable Long id) {
        desk.dismissSuggestion(id);
        return ResponseUtil.success("Dismissed", null);
    }
}
