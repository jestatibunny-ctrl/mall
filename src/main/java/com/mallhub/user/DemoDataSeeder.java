package com.mallhub.user;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds demo data on first start (only if there are no malls yet).
 * Mall names are real; coordinates are APPROXIMATE and the stores are clearly fake "Demo ..." placeholders.
 * Replace them with real data through the admin CSV import.
 */
@Component
@ConditionalOnProperty(name = "mallhub.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private record MallSeed(String name, String area, String address, double lat, double lng) {}

    private static final List<MallSeed> MALLS = List.of(
            new MallSeed("Phoenix Marketcity", "Whitefield", "Whitefield Main Road, Mahadevapura", 12.9975, 77.6960),
            new MallSeed("Orion Mall", "Rajajinagar", "Dr Rajkumar Road, Rajajinagar", 13.0110, 77.5550),
            new MallSeed("Mantri Square Mall", "Malleshwaram", "Sampige Road, Malleshwaram", 12.9914, 77.5707),
            new MallSeed("Forum Mall", "Koramangala", "Hosur Road, Koramangala", 12.9345, 77.6111),
            new MallSeed("UB City", "Vittal Mallya Road", "Vittal Mallya Road", 12.9716, 77.5955),
            new MallSeed("Garuda Mall", "Magrath Road", "Magrath Road, Ashok Nagar", 12.9699, 77.6099));

    private static final List<String> CATEGORIES = List.of(
            "Fashion", "Footwear", "Electronics", "Food Court", "Cafe", "Beauty", "Books & Gifts", "Entertainment");

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    private final boolean demoAccounts;

    public DemoDataSeeder(JdbcClient jdbc, PasswordEncoder encoder,
                          @Value("${mallhub.demo-accounts.enabled:true}") boolean demoAccounts) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.demoAccounts = demoAccounts;
    }

    @Override
    public void run(String... args) {
        Long existing = jdbc.sql("SELECT count(*) FROM malls").query(Long.class).single();
        if (existing > 0) {
            return;
        }
        log.info("Seeding demo data for {} Bengaluru malls", MALLS.size());

        for (String c : CATEGORIES) {
            jdbc.sql("INSERT INTO categories(name) VALUES (:n) ON CONFLICT (name) DO NOTHING").param("n", c).update();
        }
        List<Long> categoryIds = jdbc.sql("SELECT id FROM categories ORDER BY id").query(Long.class).list();

        Long firstMallId = null;
        for (MallSeed m : MALLS) {
            long mallId = jdbc.sql("""
                    INSERT INTO malls(name, area, address, latitude, longitude, open_time, close_time)
                    VALUES (:n, :a, :ad, :lat, :lng, TIME '10:00', TIME '22:00') RETURNING id
                    """)
                    .param("n", m.name()).param("a", m.area()).param("ad", m.address())
                    .param("lat", m.lat()).param("lng", m.lng())
                    .query(Long.class).single();
            if (firstMallId == null) {
                firstMallId = mallId;
            }

            String[] floorLabels = {"Ground", "Level 1", "Level 2", "Level 3"};
            long[] floorIds = new long[4];
            for (int level = 0; level < 4; level++) {
                floorIds[level] = jdbc.sql("INSERT INTO floors(mall_id, level, label) VALUES (:m, :l, :lbl) RETURNING id")
                        .param("m", mallId).param("l", level).param("lbl", floorLabels[level])
                        .query(Long.class).single();
            }

            int n = 0;
            for (int ci = 0; ci < categoryIds.size(); ci++) {
                for (int k = 1; k <= 2; k++) {
                    int level = (ci + k) % 4;
                    String catName = CATEGORIES.get(ci);
                    jdbc.sql("""
                            INSERT INTO stores(mall_id, floor_id, category_id, name, unit_no)
                            VALUES (:m, :f, :c, :n, :u)
                            """)
                            .param("m", mallId).param("f", floorIds[level]).param("c", categoryIds.get(ci))
                            .param("n", "Demo " + catName + " Store " + k)
                            .param("u", "L" + level + "-" + (10 + n++))
                            .update();
                }
            }

            int[][] zones = {{120}, {80}, {60}};
            String[] zoneNames = {"Basement 1", "Basement 2", "Rooftop"};
            for (int z = 0; z < zones.length; z++) {
                long zoneId = jdbc.sql("INSERT INTO parking_zones(mall_id, name, total_slots) VALUES (:m, :n, :t) RETURNING id")
                        .param("m", mallId).param("n", zoneNames[z]).param("t", zones[z][0])
                        .query(Long.class).single();
                jdbc.sql("INSERT INTO parking_status(zone_id, available_slots) VALUES (:z, :a)")
                        .param("z", zoneId).param("a", zones[z][0] / 2).update();
            }
        }

        // A few approved sample offers so the Offers page is not empty
        jdbc.sql("""
                INSERT INTO offers(store_id, title, description, discount_type, discount_value, valid_from, valid_to, status)
                SELECT id, 'Festive ' || (10 + (id % 4) * 5) || '% off', 'Sample offer', 'PERCENT',
                       10 + (id % 4) * 5, CURRENT_DATE, CURRENT_DATE + 30, 'APPROVED'
                  FROM stores ORDER BY id LIMIT 8
                """).update();

        if (demoAccounts) {
            createUser("Mall Admin", "mall.admin@mallhub.com", "admin123", Role.MALL_ADMIN, firstMallId);
            createUser("Demo User", "user@mallhub.com", "user123", Role.USER, null);
        }
    }

    private void createUser(String name, String email, String password, Role role, Long mallId) {
        jdbc.sql("""
                INSERT INTO users(name, email, password_hash, role, mall_id)
                VALUES (:n, :e, :p, :r, :m) ON CONFLICT (email) DO NOTHING
                """)
                .param("n", name).param("e", email).param("p", encoder.encode(password))
                .param("r", role.name()).param("m", mallId)
                .update();
    }
}
