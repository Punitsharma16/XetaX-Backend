package com.xetax.crm.team.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.PermissionCatalog;
import com.xetax.crm.team.service.PermissionService;
import com.xetax.crm.team.service.RequiresPermission;
import com.xetax.crm.team.service.TeamService;
import com.xetax.crm.auth.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final PermissionService permissionService;
    private final CurrentUserProvider currentUserProvider;

    /** Frontend bootstraps from this: who am I, what can I do. Not gated. */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        Map<String, Object> out = new LinkedHashMap<>();
        var user = currentUserProvider.currentUserOrNull();
        out.put("userId", user == null ? null : user.getId().toString());
        out.put("name", user == null ? null : user.getName());
        out.put("company", user == null ? null : user.getCompany());
        out.put("isOwner", permissionService.isOwner());
        out.put("role", permissionService.currentRoleName());
        out.put("permissions", permissionService.currentPermissions());
        return ResponseUtil.success("Context", out);
    }

    @GetMapping("/permissions")
    @RequiresPermission("team.manage")
    public ApiResponse<Map<String, List<PermissionCatalog.Permission>>> permissionCatalog() {
        return ResponseUtil.success("Permission catalog", PermissionCatalog.GROUPS);
    }

    /** Transfer-target dropdown — needs only records.transfer, not team.manage. */
    @GetMapping("/assignees")
    @RequiresPermission("records.transfer")
    public ApiResponse<List<Map<String, Object>>> assignees() {
        return ResponseUtil.success("Assignees", teamService.assignees());
    }

    /* -------------------------------------------------------------- roles */

    @GetMapping("/roles")
    @RequiresPermission("team.manage")
    public ApiResponse<List<Map<String, Object>>> roles() {
        return ResponseUtil.success("Roles", teamService.roles());
    }

    @PostMapping("/roles")
    @RequiresPermission("team.manage")
    public ApiResponse<Map<String, Object>> createRole(@RequestBody Map<String, Object> body) {
        return ResponseUtil.success("Role created", teamService.createRole(
                (String) body.get("name"), toList(body.get("permissions"))));
    }

    @PutMapping("/roles/{id}")
    @RequiresPermission("team.manage")
    public ApiResponse<Map<String, Object>> updateRole(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        return ResponseUtil.success("Role updated", teamService.updateRole(
                id, (String) body.get("name"), toList(body.get("permissions"))));
    }

    @DeleteMapping("/roles/{id}")
    @RequiresPermission("team.manage")
    public ApiResponse<Void> deleteRole(@PathVariable Long id) {
        teamService.deleteRole(id);
        return ResponseUtil.success("Role deleted");
    }

    /* ------------------------------------------------------------ members */

    @GetMapping("/members")
    @RequiresPermission("team.manage")
    public ApiResponse<List<Map<String, Object>>> members() {
        return ResponseUtil.success("Members", teamService.members());
    }

    @PostMapping("/members")
    @RequiresPermission("team.manage")
    public ApiResponse<Map<String, Object>> createMember(@RequestBody Map<String, Object> body) {
        return ResponseUtil.success("Member created", teamService.createMember(
                (String) body.get("name"),
                (String) body.get("email"),
                (String) body.get("password"),
                Long.valueOf(String.valueOf(body.get("roleId")))));
    }

    @PutMapping("/members/{userId}/role")
    @RequiresPermission("team.manage")
    public ApiResponse<Void> changeRole(@PathVariable String userId,
                                        @RequestBody Map<String, Object> body) {
        teamService.changeMemberRole(userId, Long.valueOf(String.valueOf(body.get("roleId"))));
        return ResponseUtil.success("Role changed");
    }

    @DeleteMapping("/members/{userId}")
    @RequiresPermission("team.manage")
    public ApiResponse<Void> removeMember(@PathVariable String userId) {
        teamService.removeMember(userId);
        return ResponseUtil.success("Member removed");
    }

    @SuppressWarnings("unchecked")
    private List<String> toList(Object value) {
        return value instanceof List ? (List<String>) value : List.of();
    }
}
