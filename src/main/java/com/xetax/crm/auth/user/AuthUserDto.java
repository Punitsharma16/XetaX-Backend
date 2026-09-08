package com.xetax.crm.auth.user;

import com.xetax.crm.auth.role.AuthRoleDto;
import com.xetax.crm.auth.token.entity.LoginProvider;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthUserDto implements Serializable {
    private UUID id;
    private String email;
    /*
     * WRITE_ONLY: the password is accepted on input (register/create/update
     * still bind it) but is NEVER serialized into any response — login's
     * userDto, getAllUsers, getUserById etc. previously leaked the BCrypt
     * hash to every caller.
     */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String name;
    private String image;
    private boolean isEnable = true;
    private boolean isAdmin;
    private String phone;
    private String company;
    private String parentId;
    private Instant createAt = Instant.now();
    private Instant updateAt = Instant.now();
    private LoginProvider provider;
    private Set<AuthRoleDto> roles = new HashSet<>();
}
