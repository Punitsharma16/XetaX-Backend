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
     * Takes one seat, and only while a seat is left. Two customers confirming
     * the same slot at the same moment both run this; the database decides,
     * and whoever's update changes no row is told the slot has just gone —
     * whether they came from the public page, the WhatsApp bot or the chat.
     *
     * <p>The slot closes on the booking that fills it, so a one-on-one slot
     * (capacity 1) goes straight from OPEN to BOOKED exactly as it always did.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BookingSlot s
               set s.bookedCount = s.bookedCount + 1,
                   s.status = case when s.bookedCount + 1 >= s.capacity
                                   then com.xetax.crm.booking.enums.SlotStatus.BOOKED
                                   else com.xetax.crm.booking.enums.SlotStatus.OPEN end,
                   s.customerName = :name, s.customerPhone = :phone,
                   s.service = :service, s.bookedVia = :via
             where s.id = :id
               and s.status = com.xetax.crm.booking.enums.SlotStatus.OPEN
               and s.bookedCount < s.capacity
            """)
    int claim(@Param("id") Long id, @Param("name") String name, @Param("phone") String phone,
              @Param("service") String service, @Param("via") String via);

    /* ------------------------------------------------ one-time catch-up */

    /** A slot written before capacity existed takes one person, like it always did. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BookingSlot s set s.capacity = 1 where s.capacity < 1")
    int backfillMissingCapacity();

    /** A slot that was already booked has one seat gone. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BookingSlot s set s.bookedCount = 1
             where s.bookedCount = 0
               and s.status = com.xetax.crm.booking.enums.SlotStatus.BOOKED
            """)
    int backfillBookedCount();

    /** Empties a slot completely and puts it back on the board. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BookingSlot s
               set s.status = com.xetax.crm.booking.enums.SlotStatus.OPEN,
                   s.bookedCount = 0,
                   s.customerName = null, s.customerPhone = null, s.service = null,
                   s.recordId = null, s.bookedVia = null
             where s.id = :id and s.ownerUserId = :owner
            """)
    int release(@Param("id") Long id, @Param("owner") String owner);

    /**
     * Gives back one seat of a group slot, which reopens it. The slot's own
     * customer fields are left alone — they mirror a booking, and the ones
     * that remain are still real; BookingService rewrites them from whoever
     * is left.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BookingSlot s
               set s.bookedCount = s.bookedCount - 1,
                   s.status = com.xetax.crm.booking.enums.SlotStatus.OPEN
             where s.id = :id and s.ownerUserId = :owner and s.bookedCount > 0
            """)
    int releaseOneSeat(@Param("id") Long id, @Param("owner") String owner);
}
