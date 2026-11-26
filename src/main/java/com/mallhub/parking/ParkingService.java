package com.mallhub.parking;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParkingService {

    public record ZoneView(long zoneId, long mallId, String mallName, String name, int totalSlots, int availableSlots) {}

    public record BookingView(long id, String mallName, String zoneName, int slotNo,
                              LocalDateTime startTime, LocalDateTime endTime, String status) {}

    public record BookingResult(String mallName, String zoneName, int slotNo, LocalDateTime endTime) {}

    private record ZoneNames(String mallName, String zoneName) {}

    private final JdbcClient jdbc;

    public ParkingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ZoneView> zones() {
        return jdbc.sql("""
                SELECT z.id AS zone_id, z.mall_id, m.name AS mall_name, z.name,
                       z.total_slots, s.available_slots
                  FROM parking_zones z
                  JOIN malls m ON m.id = z.mall_id
                  JOIN parking_status s ON s.zone_id = z.id
                 ORDER BY m.name, z.name
                """).query(ZoneView.class).list();
    }

    /**
     * Book a slot. The parking_status row is locked with SELECT ... FOR UPDATE so two people
     * clicking "book" for the last slot are serialized: one wins, the other gets ParkingFullException.
     * The partial unique index uq_active_slot is a second safety net at the database level.
     */
    @Transactional
    public BookingResult book(long userId, long zoneId, int hours) {
        if (hours < 1 || hours > 8) {
            throw new IllegalArgumentException("Choose between 1 and 8 hours");
        }
        int available = jdbc.sql("SELECT available_slots FROM parking_status WHERE zone_id = :z FOR UPDATE")
                .param("z", zoneId).query(Integer.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown parking zone"));

        Long active = jdbc.sql("SELECT count(*) FROM parking_bookings WHERE user_id = :u AND status = 'ACTIVE'")
                .param("u", userId).query(Long.class).single();
        if (active > 0) {
            throw new IllegalStateException("You already have an active parking booking");
        }
        if (available <= 0) {
            throw new ParkingFullException();
        }

        int slotNo = jdbc.sql("""
                SELECT g FROM generate_series(1, (SELECT total_slots FROM parking_zones WHERE id = :z)) AS g
                 WHERE g NOT IN (SELECT slot_no FROM parking_bookings WHERE zone_id = :z AND status = 'ACTIVE')
                 ORDER BY g LIMIT 1
                """).param("z", zoneId).query(Integer.class).optional()
                .orElseThrow(ParkingFullException::new);

        LocalDateTime end = jdbc.sql("""
                INSERT INTO parking_bookings(user_id, zone_id, slot_no, start_time, end_time)
                VALUES (:u, :z, :s, now(), now() + (:h * interval '1 hour'))
                RETURNING end_time
                """)
                .param("u", userId).param("z", zoneId).param("s", slotNo).param("h", hours)
                .query(LocalDateTime.class).single();

        jdbc.sql("UPDATE parking_status SET available_slots = available_slots - 1, updated_at = now() WHERE zone_id = :z")
                .param("z", zoneId).update();

        ZoneNames names = jdbc.sql("""
                SELECT m.name AS mall_name, z.name AS zone_name
                  FROM parking_zones z JOIN malls m ON m.id = z.mall_id WHERE z.id = :z
                """).param("z", zoneId).query(ZoneNames.class).single();
        return new BookingResult(names.mallName(), names.zoneName(), slotNo, end);
    }

    @Transactional
    public boolean cancel(long userId, long bookingId) {
        Optional<Long> zone = jdbc.sql("""
                SELECT zone_id FROM parking_bookings WHERE id = :b AND user_id = :u AND status = 'ACTIVE'
                """).param("b", bookingId).param("u", userId).query(Long.class).optional();
        if (zone.isEmpty()) {
            return false;
        }
        jdbc.sql("SELECT available_slots FROM parking_status WHERE zone_id = :z FOR UPDATE")
                .param("z", zone.get()).query(Integer.class).single();
        int n = jdbc.sql("UPDATE parking_bookings SET status = 'CANCELLED' WHERE id = :b AND status = 'ACTIVE'")
                .param("b", bookingId).update();
        if (n == 1) {
            release(zone.get());
        }
        return n == 1;
    }

    /** Bookings whose time is up are completed and their slot is freed. */
    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    @Transactional
    public void completeExpired() {
        List<Long> zones = jdbc.sql("""
                UPDATE parking_bookings SET status = 'COMPLETED'
                 WHERE status = 'ACTIVE' AND end_time < now() RETURNING zone_id
                """).query(Long.class).list();
        zones.forEach(this::release);
    }

    public List<BookingView> myBookings(long userId) {
        return jdbc.sql("""
                SELECT b.id, m.name AS mall_name, z.name AS zone_name, b.slot_no,
                       b.start_time, b.end_time, b.status
                  FROM parking_bookings b
                  JOIN parking_zones z ON z.id = b.zone_id
                  JOIN malls m ON m.id = z.mall_id
                 WHERE b.user_id = :u ORDER BY b.start_time DESC LIMIT 10
                """).param("u", userId).query(BookingView.class).list();
    }

    private void release(long zoneId) {
        jdbc.sql("""
                UPDATE parking_status
                   SET available_slots = LEAST(available_slots + 1, (SELECT total_slots FROM parking_zones WHERE id = :z)),
                       updated_at = now()
                 WHERE zone_id = :z
                """).param("z", zoneId).update();
    }
}
