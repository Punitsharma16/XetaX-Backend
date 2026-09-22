package com.xetax.crm.auth.verify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PendingSignupRepository extends JpaRepository<PendingSignup, Long> {

    Optional<PendingSignup> findByEmail(String email);

    Optional<PendingSignup> findByPhone(String phone);

    void deleteByEmail(String email);

    /** Sign-ups nobody ever confirmed — they hold an email and phone hostage. */
    @Modifying
    @Query("delete from PendingSignup p where p.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
