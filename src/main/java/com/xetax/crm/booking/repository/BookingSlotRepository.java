package com.xetax.crm.booking.repository;

import com.xetax.crm.booking.entity.BookingSlot;
import com.xetax.crm.booking.enums.SlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface BookingSlotRepository extends JpaRepository<BookingSlot, Long> {

    Optional<BookingSlot> findByIdAndOwnerUserId(Long id, String ownerUserId);

    List<BookingSlot> findByOwnerUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
            String ownerUserId, LocalDate from, LocalDate to);

    boolean existsByStaffIdAndSlotDateAndStartTime(Long staffId, LocalDate date, LocalTime startTime);

    long countByStaffIdAndStatus(Long staffId, SlotStatus status);

    void deleteByStaffId(Long staffId);

    /** Free slots from this moment on — exactly what a customer may pick. */
    @Query("""
            select s from BookingSlot s
            where s.ownerUserId = :owner and s.status = com.xetax.crm.booking.enums.SlotStatus.OPEN
              and (s.slotDate > :today or (s.slotDate = :today and s.startTime >= :now))
            order by s.slotDate asc, s.startTime asc
            """)
    List<BookingSlot> findOpenFrom(@Param("owner") String owner,
                                   @Param("today") LocalDate today,
                                   @Param("now") LocalTime now);

    /**
     * Claims a slot only while it is still OPEN. Two customers confirming the
     * same slot at the same moment both run this; exactly one gets a row back,
     * and the other is told the slot has just gone — whether they came from the
     * public page, the WhatsApp bot or the website chat.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BookingSlot s
               set s.status = com.xetax.crm.booking.enums.SlotStatus.BOOKED,
                   s.customerName = :name, s.customerPhone = :phone,
                   s.service = :service, s.bookedVia = :via
             where s.id = :id
               and s.status = com.xetax.crm.booking.enums.SlotStatus.OPEN
            """)
    int claim(@Param("id") Long id, @Param("name") String name, @Param("phone") String phone,
              @Param("service") String service, @Param("via") String via);

    /** Puts a cancelled slot back on the board, clearing who had it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BookingSlot s
               set s.status = com.xetax.crm.booking.enums.SlotStatus.OPEN,
                   s.customerName = null, s.customerPhone = null, s.service = null,
                   s.recordId = null, s.bookedVia = null
             where s.id = :id and s.ownerUserId = :owner
            """)
    int release(@Param("id") Long id, @Param("owner") String owner);
}
