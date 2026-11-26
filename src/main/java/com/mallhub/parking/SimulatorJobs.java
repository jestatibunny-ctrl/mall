package com.mallhub.parking;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stand-in for real sensors: cars randomly enter and leave, and visitors walk in.
 * Turn it off with mallhub.simulator.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "mallhub.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class SimulatorJobs {

    private final JdbcClient jdbc;
    private final ParkingBroadcaster broadcaster;

    public SimulatorJobs(JdbcClient jdbc, ParkingBroadcaster broadcaster) {
        this.jdbc = jdbc;
        this.broadcaster = broadcaster;
    }

    @Scheduled(fixedRate = 3000, initialDelay = 5000)
    public void parkingTick() {
        List<Long> zoneIds = jdbc.sql("SELECT zone_id FROM parking_status").query(Long.class).list();
        for (Long zoneId : zoneIds) {
            int delta = ThreadLocalRandom.current().nextInt(-2, 3);   // -2 .. +2
            jdbc.sql("""
                    UPDATE parking_status ps
                       SET available_slots = GREATEST(0, LEAST(z.total_slots, ps.available_slots + :d)),
                           updated_at = now()
                      FROM parking_zones z
                     WHERE z.id = ps.zone_id AND ps.zone_id = :id
                    """).param("d", delta).param("id", zoneId).update();
        }
        broadcaster.broadcast();
    }

    @Scheduled(fixedRate = 30_000, initialDelay = 10_000)
    public void footfallTick() {
        List<Long> mallIds = jdbc.sql("SELECT id FROM malls").query(Long.class).list();
        for (Long mallId : mallIds) {
            int count = ThreadLocalRandom.current().nextInt(20, 201);
            jdbc.sql("INSERT INTO footfall_events(mall_id, ts, count) VALUES (:m, now(), :c)")
                    .param("m", mallId).param("c", count).update();
        }
        jdbc.sql("DELETE FROM footfall_events WHERE ts < now() - interval '7 days'").update();
    }
}
