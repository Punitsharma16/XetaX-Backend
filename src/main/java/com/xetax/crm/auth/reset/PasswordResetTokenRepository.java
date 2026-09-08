package com.xetax.crm.auth.reset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findFirstByEmailAndUsedFalseOrderByIdDesc(String email);
    void deleteByEmail(String email);
}
