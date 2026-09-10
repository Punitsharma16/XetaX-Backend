package com.xetax.crm.auth.user;

import com.xetax.crm.auth.token.entity.LoginProvider;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "users")
public class AuthUserEntity implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id")
    private UUID id;
    @Column(name = "user_email", unique = true, length = 300)
    private String email;
    private String password;
    @Column(name = "user_name", length = 500)
    private String name;
    private boolean isEnable;
    private boolean isAdmin;
    private String company;
    private String parentId;
    private Instant createAt;
    private Instant updateAt = Instant.now();
    private String image;
    @Column(name = "phone", length = 14, unique = true)
    private String phone;
    @Enumerated(EnumType.STRING)
    private LoginProvider provider;

    /**
     * Sign-up email confirmed? null = legacy account created before the check
     * existed (treated as verified); false = must confirm before signing in.
     */
    private Boolean emailVerified;

    /**
     * Runs the XetaX platform itself, not a customer workspace: sees every
     * workspace, changes plans and AI credits. Never granted through the UI —
     * only by PLATFORM_ADMIN_EMAILS on the server or another platform admin.
     */
    private Boolean platformAdmin;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (updateAt == null) {
            updateAt = now;
        }
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getUsername() {
        return this.email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return this.isEnable;
    }
}
