package com.xetax.crm.team.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.team.entity.OrgMemberRole;
import com.xetax.crm.team.entity.OrgRole;
import com.xetax.crm.team.repository.OrgMemberRoleRepository;
import com.xetax.crm.team.repository.OrgRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Set;

/**
 * The single decision point for "kya ye user ye kaam kar sakta hai".
 *
 * Rules: org OWNER (parentId "#") => everything, always — solo users ka
 * behaviour bilkul pehle jaisa. Member => uske role ke permission keys.
 * Member permission-sets Redis me 60s cache hote hain (fail-open to DB) aur
 * role/member change par evict — change agli request se lagta hai.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private static final String CACHE_PREFIX = "xetax:perm:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final CurrentUserProvider currentUserProvider;
    private final OrgMemberRoleRepository memberRoleRepository;
    private final OrgRoleRepository roleRepository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public boolean isOwner() {
        AuthUserEntity user = currentUserProvider.currentUserOrNull();
        if (user == null) return false;
        String parentId = user.getParentId();
        // Legacy rows carry "#", "0", null… — anything that is not ANOTHER
        // user's valid UUID means "no parent", i.e. this user owns their org.
        if (parentId == null || parentId.isBlank()) return true;
        try {
            return java.util.UUID.fromString(parentId).equals(user.getId());
        } catch (IllegalArgumentException notAUuid) {
            return true;
        }
    }

    public boolean has(String permissionKey) {
        AuthUserEntity user = currentUserProvider.currentUserOrNull();
        if (user == null) return false;
        if (isOwner()) return true;
        return memberPermissions(user.getId().toString()).contains(permissionKey);
    }

    public boolean hasAny(String... permissionKeys) {
        for (String key : permissionKeys) {
            if (has(key)) return true;
        }
        return false;
    }

    /** 403 unless at least one of the keys is granted. */
    public void requireAny(String... permissionKeys) {
        if (!hasAny(permissionKeys)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Aapke role me ye permission nahi hai — apne admin se baat karo.");
        }
    }

    /** Owner, or a member holding the system ADMIN role — full-power users. */
    public boolean isAdmin() {
        if (isOwner()) return true;
        AuthUserEntity user = currentUserProvider.currentUserOrNull();
        if (user == null) return false;
        return memberRoleRepository.findByMemberUserId(user.getId().toString())
                .flatMap(mr -> roleRepository.findById(mr.getRoleId()))
                .map(OrgRole::isSystemRole)
                .orElse(false);
    }

    /** Throws 403 with a friendly message when the permission is missing. */
    public void require(String permissionKey) {
        if (!has(permissionKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Aapke role me ye permission nahi hai (" + permissionKey
                            + ") — apne admin se baat karo.");
        }
    }

    public Set<String> currentPermissions() {
        AuthUserEntity user = currentUserProvider.currentUserOrNull();
        if (user == null) return Set.of();
        if (isOwner()) return PermissionCatalog.ALL_KEYS;
        return memberPermissions(user.getId().toString());
    }

    public String currentRoleName() {
        if (isOwner()) return "ADMIN";
        AuthUserEntity user = currentUserProvider.currentUserOrNull();
        if (user == null) return "";
        return memberRoleRepository.findByMemberUserId(user.getId().toString())
                .flatMap(mr -> roleRepository.findById(mr.getRoleId()))
                .map(OrgRole::getName)
                .orElse("NO ROLE");
    }

    /**
     * Permission check for ANY member (not the caller) — background jobs use
     * it to pick who gets a bell. The org owner always has everything.
     */
    public boolean memberHas(String ownerUserId, String memberUserId, String permissionKey) {
        if (memberUserId == null) return false;
        if (memberUserId.equals(ownerUserId)) return true;
        return memberPermissions(memberUserId).contains(permissionKey);
    }

    /* ------------------------------------------------------------- lookup */

    private Set<String> memberPermissions(String memberUserId) {
        String cacheKey = CACHE_PREFIX + memberUserId;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<Set<String>>() {});
            }
        } catch (Exception e) {
            log.debug("Perm cache read skipped: {}", e.getMessage());
        }

        Set<String> permissions = memberRoleRepository.findByMemberUserId(memberUserId)
                .flatMap(memberRole -> roleRepository.findById(memberRole.getRoleId()))
                .map(this::parsePermissions)
                .orElse(Set.of());

        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(permissions), CACHE_TTL);
        } catch (Exception e) {
            log.debug("Perm cache write skipped: {}", e.getMessage());
        }
        return permissions;
    }

    Set<String> parsePermissions(OrgRole role) {
        if (role.isSystemRole()) return PermissionCatalog.ALL_KEYS;
        try {
            if (role.getPermissionsJson() == null || role.getPermissionsJson().isBlank()) {
                return Set.of();
            }
            return objectMapper.readValue(role.getPermissionsJson(),
                    new TypeReference<Set<String>>() {});
        } catch (Exception e) {
            log.warn("Bad permissionsJson on role {}: {}", role.getId(), e.getMessage());
            return Set.of();
        }
    }

    public void evictMember(String memberUserId) {
        try {
            redis.delete(CACHE_PREFIX + memberUserId);
        } catch (Exception e) {
            log.debug("Perm cache evict skipped: {}", e.getMessage());
        }
    }

    public void evictRoleMembers(Long roleId, String ownerUserId) {
        for (OrgMemberRole memberRole : memberRoleRepository.findByOwnerUserId(ownerUserId)) {
            if (memberRole.getRoleId().equals(roleId)) {
                evictMember(memberRole.getMemberUserId());
            }
        }
    }
}
