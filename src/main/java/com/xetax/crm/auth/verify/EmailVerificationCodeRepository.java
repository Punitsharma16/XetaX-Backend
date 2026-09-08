package com.xetax.crm.auth.verify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {
    Optional<EmailVerificationCode> findFirstByEmailAndUsedFalseOrderByIdDesc(String email);
    void deleteByEmail(String email);
}
