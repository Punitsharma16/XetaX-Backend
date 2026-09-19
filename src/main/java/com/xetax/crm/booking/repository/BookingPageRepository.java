package com.xetax.crm.booking.repository;

import com.xetax.crm.booking.entity.BookingPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingPageRepository extends JpaRepository<BookingPage, Long> {

    Optional<BookingPage> findFirstByOwnerUserIdOrderByIdAsc(String ownerUserId);

    Optional<BookingPage> findByPublicKey(String publicKey);

    boolean existsByOwnerUserId(String ownerUserId);
}
