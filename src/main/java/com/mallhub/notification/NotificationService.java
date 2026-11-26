package com.mallhub.notification;

import com.mallhub.offer.OfferApprovedEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    public record NotificationView(long id, String type, String payload, LocalDateTime createdAt, LocalDateTime readAt) {}

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcClient jdbc;
    private final EmailSender email;

    public NotificationService(JdbcClient jdbc, EmailSender email) {
        this.jdbc = jdbc;
        this.email = email;
    }

    /**
     * Runs on a background thread so approving an offer never waits for notifications.
     * (Spring events + @Async here; the same shape moves to RabbitMQ/Kafka later.)
     */
    @Async
    @EventListener
    public void onOfferApproved(OfferApprovedEvent e) {
        String message = "New offer at " + e.storeName() + " (" + e.mallName() + "): " + e.title();
        int n = jdbc.sql("""
                INSERT INTO notifications(user_id, type, payload, sent_at)
                SELECT id, 'NEW_OFFER', :p, now() FROM users WHERE role = 'USER'
                """).param("p", message).update();
        List<String> emails = jdbc.sql("SELECT email FROM users WHERE role = 'USER'").query(String.class).list();
        emails.forEach(to -> email.send(to, "New offer on MallHub", message));
        log.info("Offer {} approved: {} in-app notifications created", e.offerId(), n);
    }

    public List<NotificationView> forUser(long userId) {
        return jdbc.sql("""
                SELECT id, type, payload, created_at, read_at FROM notifications
                 WHERE user_id = :u ORDER BY created_at DESC, id DESC LIMIT 50
                """).param("u", userId).query(NotificationView.class).list();
    }

    public long unreadCount(long userId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :u AND read_at IS NULL")
                .param("u", userId).query(Long.class).single();
    }

    public void markAllRead(long userId) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL")
                .param("u", userId).update();
    }
}
