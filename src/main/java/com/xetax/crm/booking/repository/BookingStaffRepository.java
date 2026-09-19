package com.xetax.crm.booking.repository;

import com.xetax.crm.booking.entity.BookingStaff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingStaffRepository extends JpaRepository<BookingStaff, Long> {

    List<BookingStaff> findByOwnerUserIdOrderBySortOrderAscIdAsc(String ownerUserId);

    List<BookingStaff> findByOwnerUserIdAndActiveTrueOrderBySortOrderAscIdAsc(String ownerUserId);

    Optional<BookingStaff> findByIdAndOwnerUserId(Long id, String ownerUserId);
}
