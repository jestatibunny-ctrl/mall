package com.mallhub.loyalty;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LoyaltyService {

    /** 1 point for every 100 rupees on a verified bill. */
    public static final int RUPEES_PER_POINT = 100;

    public record LedgerRow(long id, int points, String type, LocalDateTime createdAt, Long billId) {}

    public record BillView(long id, String userName, String storeName, String mallName, String billNo,
                           BigDecimal amount, String imageUrl, String status, LocalDateTime createdAt) {}

    private record BillRow(long userId, BigDecimal amount) {}

    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "pdf");

    private final JdbcClient jdbc;
    private final String uploadDir;

    public LoyaltyService(JdbcClient jdbc, @Value("${mallhub.upload-dir:uploads}") String uploadDir) {
        this.jdbc = jdbc;
        this.uploadDir = uploadDir;
    }

    public Path uploadPath() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public long balance(long userId) {
        return jdbc.sql("SELECT COALESCE(SUM(points), 0) FROM points_ledger WHERE user_id = :u")
                .param("u", userId).query(Long.class).single();
    }

    public List<LedgerRow> ledger(long userId) {
        return jdbc.sql("""
                SELECT id, points, type, created_at, bill_id FROM points_ledger
                 WHERE user_id = :u ORDER BY id DESC LIMIT 30
                """).param("u", userId).query(LedgerRow.class).list();
    }

    private static final String BILL_SELECT = """
            SELECT b.id, u.name AS user_name, s.name AS store_name, m.name AS mall_name, b.bill_no,
                   b.amount, b.image_url, b.status, b.created_at
              FROM bills b
              JOIN users u ON u.id = b.user_id
              JOIN stores s ON s.id = b.store_id
              JOIN malls m ON m.id = s.mall_id
            """;

    public List<BillView> myBills(long userId) {
        return jdbc.sql(BILL_SELECT + " WHERE b.user_id = :u ORDER BY b.id DESC LIMIT 20")
                .param("u", userId).query(BillView.class).list();
    }

    public List<BillView> pendingBills() {
        return jdbc.sql(BILL_SELECT + " WHERE b.status = 'PENDING' ORDER BY b.id").query(BillView.class).list();
    }

    public void submitBill(long userId, long storeId, String billNo, BigDecimal amount, MultipartFile image)
            throws IOException {
        if (billNo == null || billNo.isBlank()) {
            throw new IllegalArgumentException("Bill number is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        String stored = saveImage(image);
        try {
            jdbc.sql("""
                    INSERT INTO bills(user_id, store_id, bill_no, amount, image_url)
                    VALUES (:u, :s, :n, :a, :i)
                    """)
                    .param("u", userId).param("s", storeId).param("n", billNo.trim())
                    .param("a", amount).param("i", stored)
                    .update();
        } catch (DuplicateKeyException e) {
            deleteQuietly(stored);
            // UNIQUE (store_id, bill_no): the same bill can never earn points twice
            throw new IllegalArgumentException("This bill was already submitted");
        }
    }

    /** Verifying a bill credits points exactly once, even if the admin double-clicks. */
    @Transactional
    public boolean verify(long billId) {
        Optional<BillRow> bill = jdbc.sql("""
                UPDATE bills SET status = 'VERIFIED' WHERE id = :id AND status = 'PENDING'
                RETURNING user_id, amount
                """).param("id", billId).query(BillRow.class).optional();
        if (bill.isEmpty()) {
            return false;
        }
        int points = bill.get().amount().intValue() / RUPEES_PER_POINT;
        if (points > 0) {
            jdbc.sql("""
                    INSERT INTO points_ledger(user_id, bill_id, points, type, idempotency_key)
                    VALUES (:u, :b, :p, 'EARN', :k) ON CONFLICT (idempotency_key) DO NOTHING
                    """)
                    .param("u", bill.get().userId()).param("b", billId).param("p", points)
                    .param("k", "bill-" + billId + "-earn").update();
        }
        return true;
    }

    public boolean reject(long billId) {
        return jdbc.sql("UPDATE bills SET status = 'REJECTED' WHERE id = :id AND status = 'PENDING'")
                .param("id", billId).update() == 1;
    }

    /**
     * Redeem points. The user row is locked so two simultaneous redemptions cannot overspend,
     * and the idempotency key (sent by the form) makes a double-submit harmless.
     * Returns false if this exact request was already processed.
     */
    @Transactional
    public boolean redeem(long userId, int points, String clientKey) {
        if (points <= 0) {
            throw new IllegalArgumentException("Enter a positive number of points");
        }
        String key = "redeem-" + userId + "-" + clientKey;
        jdbc.sql("SELECT id FROM users WHERE id = :u FOR UPDATE").param("u", userId).query(Long.class).single();

        Long seen = jdbc.sql("SELECT count(*) FROM points_ledger WHERE idempotency_key = :k")
                .param("k", key).query(Long.class).single();
        if (seen > 0) {
            return false;
        }
        if (points > balance(userId)) {
            throw new IllegalArgumentException("Not enough points");
        }
        jdbc.sql("""
                INSERT INTO points_ledger(user_id, points, type, idempotency_key)
                VALUES (:u, :p, 'REDEEM', :k)
                """).param("u", userId).param("p", -points).param("k", key).update();
        return true;
    }

    private String saveImage(MultipartFile f) throws IOException {
        if (f == null || f.isEmpty()) {
            return null;
        }
        String original = f.getOriginalFilename() == null ? "" : f.getOriginalFilename().toLowerCase();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1) : "";
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("Upload a JPG, PNG, WEBP or PDF file");
        }
        Path dir = uploadPath();
        Files.createDirectories(dir);
        String name = UUID.randomUUID() + "." + ext;
        f.transferTo(dir.resolve(name));
        return name;
    }

    private void deleteQuietly(String name) {
        if (name == null) {
            return;
        }
        try {
            Files.deleteIfExists(uploadPath().resolve(name));
        } catch (IOException ignored) {
            // best effort
        }
    }
}
