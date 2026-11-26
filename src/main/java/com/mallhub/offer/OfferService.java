package com.mallhub.offer;

import com.mallhub.user.UserService.UserRow;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class OfferService {

    // Serializable because the active-offers list is cached in Redis
    public record OfferView(long id, long storeId, String storeName, long mallId, String mallName,
                            String title, String description, String discountType, BigDecimal discountValue,
                            LocalDate validFrom, LocalDate validTo, String status) implements Serializable {}

    private static final String BASE = """
            SELECT o.id, o.store_id, s.name AS store_name, s.mall_id, m.name AS mall_name,
                   o.title, o.description, o.discount_type, o.discount_value,
                   o.valid_from, o.valid_to, o.status
              FROM offers o
              JOIN stores s ON s.id = o.store_id
              JOIN malls m ON m.id = s.mall_id
            """;

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public OfferService(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Approved offers valid today. Cached in Redis for 60s and evicted whenever an offer is approved. */
    @Cacheable("activeOffers")
    public List<OfferView> active() {
        return new ArrayList<>(jdbc.sql(BASE + """
                 WHERE o.status = 'APPROVED' AND CURRENT_DATE BETWEEN o.valid_from AND o.valid_to
                 ORDER BY o.valid_to, o.id
                """).query(OfferView.class).list());
    }

    /** All offers for the admin screen. A mall admin only sees their own mall. */
    public List<OfferView> forAdmin(UserRow user) {
        if ("MALL_ADMIN".equals(user.role()) && user.mallId() != null) {
            return jdbc.sql(BASE + " WHERE s.mall_id = :m ORDER BY o.created_at DESC LIMIT 200")
                    .param("m", user.mallId()).query(OfferView.class).list();
        }
        return jdbc.sql(BASE + " ORDER BY o.created_at DESC LIMIT 200").query(OfferView.class).list();
    }

    public void create(UserRow user, long storeId, String title, String description, String discountType,
                       BigDecimal discountValue, LocalDate from, LocalDate to) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Choose valid dates (end must not be before start)");
        }
        if (discountValue == null || discountValue.signum() < 0) {
            throw new IllegalArgumentException("Discount value cannot be negative");
        }
        Long storeMall = jdbc.sql("SELECT mall_id FROM stores WHERE id = :s").param("s", storeId)
                .query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown store"));
        if ("MALL_ADMIN".equals(user.role()) && !storeMall.equals(user.mallId())) {
            throw new AccessDeniedException("You can only create offers for your own mall");
        }
        jdbc.sql("""
                INSERT INTO offers(store_id, title, description, discount_type, discount_value, valid_from, valid_to, status)
                VALUES (:s, :t, :d, :dt, :dv, :f, :to, 'PENDING')
                """)
                .param("s", storeId).param("t", title.trim()).param("d", description)
                .param("dt", discountType).param("dv", discountValue).param("f", from).param("to", to)
                .update();
    }

    @CacheEvict(value = "activeOffers", allEntries = true)
    public boolean approve(long offerId) {
        int n = jdbc.sql("UPDATE offers SET status = 'APPROVED' WHERE id = :id AND status = 'PENDING'")
                .param("id", offerId).update();
        if (n == 0) {
            return false;
        }
        Optional<OfferView> offer = jdbc.sql(BASE + " WHERE o.id = :id").param("id", offerId)
                .query(OfferView.class).optional();
        offer.ifPresent(o -> events.publishEvent(
                new OfferApprovedEvent(o.id(), o.title(), o.storeName(), o.mallName())));
        return true;
    }

    @CacheEvict(value = "activeOffers", allEntries = true)
    public boolean reject(long offerId) {
        return jdbc.sql("UPDATE offers SET status = 'REJECTED' WHERE id = :id AND status = 'PENDING'")
                .param("id", offerId).update() == 1;
    }
}
