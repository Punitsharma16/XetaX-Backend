package com.xetax.crm.team.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.auth.user.AuthUserDto;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.auth.user.AuthUserService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.exception.UnauthorizedException;
import com.xetax.crm.team.entity.OrgMemberRole;
import com.xetax.crm.team.entity.OrgRole;
import com.xetax.crm.team.repository.OrgMemberRoleRepository;
import com.xetax.crm.team.repository.OrgRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Members + custom roles of one organization (org == owner user; members are
 * auth users whose parentId = owner id). Owner-scoped everywhere. Existing
 * solo users are untouched — an org only "appears" once a member is added.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {

    private final OrgRoleRepository roleRepository;
    private final OrgMemberRoleRepository memberRoleRepository;
    private final AuthUserRepository userRepository;
    private final com.xetax.crm.billing.PlanLimitService planLimitService;
    private final AuthUserService authUserService;
    private final CurrentUserProvider currentUserProvider;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    private String ownerId() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new UnauthorizedException("Not authenticated");
        return id.toString();
    }

    /* -------------------------------------------------------------- roles */

    @Transactional
    public List<Map<String, Object>> roles() {
        ensureAdminRole();
        return roleRepository.findByOwnerUserIdOrderByIdAsc(ownerId()).stream()
                .map(this::roleMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> createRole(String name, List<String> permissions) {
        String owner = ownerId();
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Role ka naam zaroori hai");
        }
        String cleanName = name.trim().toUpperCase().replaceAll("\\s+", "_");
        if (roleRepository.findByOwnerUserIdAndNameIgnoreCase(owner, cleanName).isPresent()) {
            throw new BadRequestException("Is naam ka role pehle se hai");
        }
        OrgRole role = roleRepository.save(OrgRole.builder()
                .ownerUserId(owner)
                .name(cleanName)
                .permissionsJson(writePermissions(permissions))
                .systemRole(false)
                .build());
        return roleMap(role);
    }

    @Transactional
    public Map<String, Object> updateRole(Long roleId, String name, List<String> permissions) {
        OrgRole role = requireRole(roleId);
        if (role.isSystemRole()) {
            throw new BadRequestException("ADMIN role change nahi ho sakta");
        }
        if (name != null && !name.isBlank()) {
            role.setName(name.trim().toUpperCase().replaceAll("\\s+", "_"));
        }
        if (permissions != null) {
            role.setPermissionsJson(writePermissions(permissions));
        }
        role = roleRepository.save(role);
        permissionService.evictRoleMembers(role.getId(), role.getOwnerUserId());
        return roleMap(role);
    }

    @Transactional
    public void deleteRole(Long roleId) {
        OrgRole role = requireRole(roleId);
        if (role.isSystemRole()) {
            throw new BadRequestException("ADMIN role delete nahi ho sakta");
        }
        if (memberRoleRepository.countByRoleId(roleId) > 0) {
            throw new BadRequestException(
                    "Is role par members hain — pehle unka role badlo, phir delete karo");
        }
        roleRepository.delete(role);
    }

    /* ------------------------------------------------------------ members */

    public List<Map<String, Object>> members() {
        String owner = ownerId();
        Map<String, OrgMemberRole> roleByMember = new HashMap<>();
        memberRoleRepository.findByOwnerUserId(owner)
                .forEach(mr -> roleByMember.put(mr.getMemberUserId(), mr));
        Map<Long, OrgRole> rolesById = new HashMap<>();
        roleRepository.findByOwnerUserIdOrderByIdAsc(owner)
                .forEach(r -> rolesById.put(r.getId(), r));

        List<Map<String, Object>> out = new ArrayList<>();
        for (AuthUserEntity member : userRepository.findByParentId(owner)) {
            OrgMemberRole memberRole = roleByMember.get(member.getId().toString());
            OrgRole role = memberRole == null ? null : rolesById.get(memberRole.getRoleId());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", member.getId().toString());
            map.put("name", member.getName());
            map.put("email", member.getEmail());
            map.put("roleId", role == null ? null : role.getId());
            map.put("roleName", role == null ? "NO ROLE" : role.getName());
            out.add(map);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createMember(String name, String email, String password, Long roleId) {
        planLimitService.assertCanAddMember(ownerId());
        String owner = ownerId();
        OrgRole role = requireRole(roleId);
        if (password == null || password.length() < 6) {
            throw new BadRequestException("Password kam se kam 6 characters ka ho");
        }
        AuthUserDto dto = new AuthUserDto();
        dto.setName(name);
        dto.setEmail(email);
        dto.setPassword(password);
        dto.setParentId(owner);
        AuthUserDto created = authUserService.createUser(dto);

        memberRoleRepository.save(OrgMemberRole.builder()
                .ownerUserId(owner)
                .memberUserId(created.getId().toString())
                .roleId(role.getId())
                .build());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", created.getId().toString());
        map.put("name", created.getName());
        map.put("email", created.getEmail());
        map.put("roleId", role.getId());
        map.put("roleName", role.getName());
        return map;
    }

    @Transactional
    public void changeMemberRole(String memberUserId, Long roleId) {
        String owner = ownerId();
        OrgRole role = requireRole(roleId);
        OrgMemberRole memberRole = memberRoleRepository.findByMemberUserId(memberUserId)
                .filter(mr -> mr.getOwnerUserId().equals(owner))
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        memberRole.setRoleId(role.getId());
        memberRoleRepository.save(memberRole);
        permissionService.evictMember(memberUserId);
    }

    /** Detach: member apni khud ki (khali) org ban jata hai; account delete nahi hota. */
    @Transactional
    public void removeMember(String memberUserId) {
        String owner = ownerId();
        AuthUserEntity member = userRepository.findById(UUID.fromString(memberUserId))
                .filter(u -> owner.equals(u.getParentId()))
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        member.setParentId("#");
        userRepository.save(member);
        memberRoleRepository.findByMemberUserId(memberUserId)
                .ifPresent(memberRoleRepository::delete);
        permissionService.evictMember(memberUserId);
    }

    /** Owner + members, id/name only — the record-transfer target dropdown. */
    public List<Map<String, Object>> assignees() {
        String owner = ownerId();
        List<Map<String, Object>> out = new ArrayList<>();
        userRepository.findById(UUID.fromString(owner)).ifPresent(ownerUser -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", ownerUser.getId().toString());
            map.put("name", (ownerUser.getName() == null ? "Owner" : ownerUser.getName()) + " (Admin)");
            out.add(map);
        });
        for (AuthUserEntity member : userRepository.findByParentId(owner)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", member.getId().toString());
            map.put("name", member.getName());
            out.add(map);
        }
        return out;
    }

    /** True when the given user id is the owner or one of the org's members. */
    /** Display name for a member id — name, else email, else the id itself. */
    public String memberDisplayName(String userId) {
        try {
            return userRepository.findById(java.util.UUID.fromString(userId))
                    .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getEmail())
                    .orElse(userId);
        } catch (Exception e) {
            return userId;
        }
    }

    public boolean isInMyOrg(String userId) {
        String owner = ownerId();
        if (owner.equals(userId)) return true;
        return userRepository.findById(UUID.fromString(userId))
                .map(u -> owner.equals(u.getParentId()))
                .orElse(false);
    }

    /* ------------------------------------------------------------ helpers */

    private void ensureAdminRole() {
        String owner = ownerId();
        if (roleRepository.findByOwnerUserIdAndNameIgnoreCase(owner, "ADMIN").isEmpty()) {
            roleRepository.save(OrgRole.builder()
                    .ownerUserId(owner).name("ADMIN")
                    .permissionsJson(null).systemRole(true).build());
        }
    }

    private OrgRole requireRole(Long roleId) {
        return roleRepository.findByIdAndOwnerUserId(roleId, ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
    }

    private String writePermissions(List<String> permissions) {
        List<String> valid = permissions == null ? List.of() : permissions.stream()
                .filter(PermissionCatalog.ALL_KEYS::contains)
                .distinct()
                .toList();
        try {
            return objectMapper.writeValueAsString(valid);
        } catch (Exception e) {
            throw new BadRequestException("Permissions parse nahi hui");
        }
    }

    private Map<String, Object> roleMap(OrgRole role) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", role.getId());
        map.put("name", role.getName());
        map.put("system", role.isSystemRole());
        map.put("permissions", permissionService.parsePermissions(role));
        map.put("memberCount", memberRoleRepository.countByRoleId(role.getId()));
        return map;
    }
}
