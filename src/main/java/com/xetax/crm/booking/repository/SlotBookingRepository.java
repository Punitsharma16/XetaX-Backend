package com.xetax.crm.booking.repository;

import com.xetax.crm.booking.entity.SlotBooking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SlotBookingRepository extends JpaRepository<SlotBooking, Long> {

    List<SlotBooking> findBySlotIdOrderByIdAsc(Long slotId);

    List<SlotBooking> findBySlotIdInOrderByIdAsc(Collection<Long> slotIds);

    Optional<SlotBooking> findByIdAndOwnerUserId(Long id, String ownerUserId);

    void deleteBySlotId(Long slotId);
}
