package com.mallhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mallhub.loyalty.LoyaltyService;
import com.mallhub.parking.ParkingFullException;
import com.mallhub.parking.ParkingService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Needs Docker (Testcontainers starts a real PostgreSQL). Proves the locking and idempotency claims. */
@SpringBootTest(properties = {"mallhub.simulator.enabled=false", "mallhub.demo-data.enabled=false"})
@Testcontainers
class ConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired JdbcClient jdbc;
    @Autowired ParkingService parking;
    @Autowired LoyaltyService loyalty;

    private long newUser() {
        return jdbc.sql("""
                INSERT INTO users(name, email, password_hash, role)
                VALUES ('Test', :e, 'x', 'USER') RETURNING id
                """).param("e", UUID.randomUUID() + "@test.com").query(Long.class).single();
    }

    private long newMall() {
        return jdbc.sql("INSERT INTO malls(name, latitude, longitude) VALUES ('Test Mall', 12.9, 77.6) RETURNING id")
                .query(Long.class).single();
    }

    private long newStore(long mallId) {
        return jdbc.sql("INSERT INTO stores(mall_id, name) VALUES (:m, 'Test Store') RETURNING id")
                .param("m", mallId).query(Long.class).single();
    }

    @Test
    void fiftyPeopleCompeteForTenParkingSlots() throws Exception {
        long mall = newMall();
        long zone = jdbc.sql("INSERT INTO parking_zones(mall_id, name, total_slots) VALUES (:m, 'Z', 10) RETURNING id")
                .param("m", mall).query(Long.class).single();
        jdbc.sql("INSERT INTO parking_status(zone_id, available_slots) VALUES (:z, 10)").param("z", zone).update();

        List<Long> users = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            users.add(newUser());
        }

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        List<Future<Void>> futures = new ArrayList<>();
        for (long uid : users) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    parking.book(uid, zone, 1);
                    ok.incrementAndGet();
                } catch (ParkingFullException e) {
                    full.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<Void> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(10, ok.get());
        assertEquals(40, full.get());
        assertEquals(0, jdbc.sql("SELECT available_slots FROM parking_status WHERE zone_id = :z")
                .param("z", zone).query(Integer.class).single());
        assertEquals(10, jdbc.sql("SELECT count(DISTINCT slot_no) FROM parking_bookings WHERE zone_id = :z AND status = 'ACTIVE'")
                .param("z", zone).query(Integer.class).single());
    }

    @Test
    void verifyingABillTwiceCreditsPointsOnce() throws Exception {
        long user = newUser();
        long store = newStore(newMall());
        loyalty.submitBill(user, store, "B-1", new BigDecimal("500"), null);
        long billId = jdbc.sql("SELECT id FROM bills WHERE user_id = :u").param("u", user)
                .query(Long.class).single();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> loyalty.verify(billId)));
        }
        int successes = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdown();

        assertEquals(1, successes);
        assertEquals(5, loyalty.balance(user));   // 500 rupees = 5 points
    }

    @Test
    void duplicateBillNumberIsRejected() throws Exception {
        long user = newUser();
        long store = newStore(newMall());
        loyalty.submitBill(user, store, "SAME", new BigDecimal("100"), null);
        assertThrows(IllegalArgumentException.class,
                () -> loyalty.submitBill(user, store, "SAME", new BigDecimal("100"), null));
    }

    @Test
    void redeemIsIdempotentAndCannotOverspend() throws Exception {
        long user = newUser();
        long store = newStore(newMall());
        loyalty.submitBill(user, store, "R-1", new BigDecimal("1000"), null);
        long billId = jdbc.sql("SELECT id FROM bills WHERE user_id = :u").param("u", user)
                .query(Long.class).single();
        assertTrue(loyalty.verify(billId));
        assertEquals(10, loyalty.balance(user));

        assertTrue(loyalty.redeem(user, 4, "key-1"));
        assertFalse(loyalty.redeem(user, 4, "key-1"));   // same request again: ignored
        assertEquals(6, loyalty.balance(user));
        assertThrows(IllegalArgumentException.class, () -> loyalty.redeem(user, 7, "key-2"));
        assertEquals(6, loyalty.balance(user));
    }
}
