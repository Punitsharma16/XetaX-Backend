package com.xetax.crm.team.tools;

import com.xetax.crm.data_manager.service.RecordService;
import com.xetax.crm.team.service.PermissionCatalog;
import com.xetax.crm.team.service.RequiresPermission;
import com.xetax.crm.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI tools for team/roles/record-transfer. Every method is gated by
 * the same @RequiresPermission aspect as REST — a member whose role lacks
 * team.manage can't do any of this through the assistant either.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamTools {

    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789@#";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TeamService teamService;
    private final RecordService recordService;

    @Tool(description = "List all available permission keys (grouped by module) that a "
            + "custom role can be given.")
    @RequiresPermission("team.manage")
    public Map<String, List<String>> getPermissionCatalog() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        PermissionCatalog.GROUPS.forEach((group, perms) -> out.put(group,
                perms.stream().map(PermissionCatalog.Permission::key).toList()));
        return out;
    }

    @Tool(description = "List this organization's roles with their permissions and member counts.")
    @RequiresPermission("team.manage")
    public List<Map<String, Object>> getTeamRoles() {
        return teamService.roles();
    }

    @Tool(description = "Create a custom role. permissions must be keys from "
            + "getPermissionCatalog (e.g. records.view, records.edit, whatsapp.inbox). "
            + "Use ONLY on an explicit user request; confirm the permission list first.")
    @RequiresPermission("team.manage")
    public Map<String, Object> createTeamRole(
            @ToolParam(description = "Role name, e.g. SALES_AGENT") String name,
            @ToolParam(description = "Permission keys to allow") List<String> permissions) {
        try {
            return teamService.createRole(name, permissions);
        } catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = "List the organization's team members with their roles.")
    @RequiresPermission("team.manage")
    public List<Map<String, Object>> getTeamMembers() {
        return teamService.members();
    }

    @Tool(description = "Create a team member with a role. A temporary password is generated "
            + "and returned ONCE — tell the user to share it with the member and have them "
            + "change it. Resolve the role by name via getTeamRoles first. "
            + "Use ONLY on an explicit user request.")
    @RequiresPermission("team.manage")
    public Map<String, Object> createTeamMember(
            @ToolParam(description = "Member's full name") String name,
            @ToolParam(description = "Member's email (their login)") String email,
            @ToolParam(description = "Role id from getTeamRoles") Long roleId) {
        try {
            String tempPassword = generatePassword();
            Map<String, Object> created =
                    new LinkedHashMap<>(teamService.createMember(name, email, tempPassword, roleId));
            created.put("temporaryPassword", tempPassword);
            created.put("note", "Ye password sirf ek baar dikh raha hai — member ko dekar "
                    + "pehle login par change karwa lena.");
            return created;
        } catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = "Change an existing member's role. Resolve ids via getTeamMembers "
            + "and getTeamRoles.")
    @RequiresPermission("team.manage")
    public Map<String, Object> changeMemberRole(
            @ToolParam(description = "Member userId from getTeamMembers") String memberUserId,
            @ToolParam(description = "New role id from getTeamRoles") Long roleId) {
        try {
            teamService.changeMemberRole(memberUserId, roleId);
            return Map.of("changed", true);
        } catch (Exception e) {
            return Map.of("changed", false, "error", safeMessage(e));
        }
    }

    @Tool(description = "Transfer ALL records currently assigned to one member onto another "
            + "member (bulk hand-over, e.g. when an agent leaves). Resolve userIds via "
            + "getTeamMembers. Use ONLY on an explicit user request.")
    @RequiresPermission("records.transfer")
    public Map<String, Object> transferAllRecords(
            @ToolParam(description = "userId records FROM (current assignee)") String fromUserId,
            @ToolParam(description = "userId records go TO") String toUserId) {
        try {
            int moved = recordService.transferAll(fromUserId, toUserId);
            return Map.of("moved", moved);
        } catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = "Assign specific records (by their ids) to a team member.")
    @RequiresPermission("records.transfer")
    public Map<String, Object> transferRecords(
            @ToolParam(description = "Record ids to move") List<String> recordIds,
            @ToolParam(description = "userId records go TO") String toUserId) {
        try {
            int moved = recordService.transfer(new ArrayList<>(recordIds), toUserId);
            return Map.of("moved", moved);
        } catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    private String generatePassword() {
        StringBuilder password = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            password.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Action fail ho gaya." : message;
    }
}
