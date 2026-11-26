package com.mallhub.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class StoreService {

    public record StoreView(long id, long mallId, String mallName, String floorLabel, String category,
                            String name, String unitNo, String contact, long activeOffers) {}

    public record Category(long id, String name) {}

    public record ImportResult(int imported, int skipped, List<String> errors) {}

    private final JdbcClient jdbc;

    public StoreService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Cross-mall search. Pass "" / 0 / 0 for "no filter". */
    public List<StoreView> search(String q, long categoryId, long mallId) {
        return jdbc.sql("""
                SELECT s.id, s.mall_id, m.name AS mall_name, COALESCE(f.label, '') AS floor_label,
                       COALESCE(c.name, '') AS category, s.name, s.unit_no, s.contact,
                       (SELECT count(*) FROM offers o
                         WHERE o.store_id = s.id AND o.status = 'APPROVED'
                           AND CURRENT_DATE BETWEEN o.valid_from AND o.valid_to) AS active_offers
                  FROM stores s
                  JOIN malls m ON m.id = s.mall_id
                  LEFT JOIN floors f ON f.id = s.floor_id
                  LEFT JOIN categories c ON c.id = s.category_id
                 WHERE (:q = '' OR lower(s.name) LIKE lower('%' || :q || '%'))
                   AND (:cat = 0 OR s.category_id = :cat)
                   AND (:mall = 0 OR s.mall_id = :mall)
                 ORDER BY m.name, s.name
                 LIMIT 200
                """)
                .param("q", q == null ? "" : q.trim())
                .param("cat", categoryId)
                .param("mall", mallId)
                .query(StoreView.class)
                .list();
    }

    public List<Category> categories() {
        return jdbc.sql("SELECT id, name FROM categories ORDER BY name").query(Category.class).list();
    }

    public long findOrCreateCategory(String name) {
        jdbc.sql("INSERT INTO categories(name) VALUES (:n) ON CONFLICT (name) DO NOTHING")
                .param("n", name).update();
        return jdbc.sql("SELECT id FROM categories WHERE name = :n").param("n", name)
                .query(Long.class).single();
    }

    public long findOrCreateFloor(long mallId, int level) {
        String label = level == 0 ? "Ground" : level < 0 ? "Basement " + (-level) : "Level " + level;
        jdbc.sql("INSERT INTO floors(mall_id, level, label) VALUES (:m, :l, :lbl) ON CONFLICT (mall_id, level) DO NOTHING")
                .param("m", mallId).param("l", level).param("lbl", label).update();
        return jdbc.sql("SELECT id FROM floors WHERE mall_id = :m AND level = :l")
                .param("m", mallId).param("l", level).query(Long.class).single();
    }

    public long createStore(long mallId, int level, String category, String name, String unitNo, String contact) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Store name is required");
        }
        long floorId = findOrCreateFloor(mallId, level);
        long categoryId = findOrCreateCategory(category == null || category.isBlank() ? "Other" : category.trim());
        return jdbc.sql("""
                INSERT INTO stores(mall_id, floor_id, category_id, name, unit_no, contact)
                VALUES (:m, :f, :c, :n, :u, :t) RETURNING id
                """)
                .param("m", mallId).param("f", floorId).param("c", categoryId)
                .param("n", name.trim()).param("u", blankToNull(unitNo)).param("t", blankToNull(contact))
                .query(Long.class).single();
    }

    /**
     * CSV columns: mall_name,floor,category,store_name,unit_no,contact
     * floor is a number (0 = ground, -1 = basement 1) or G / Ground.
     */
    public ImportResult importCsv(InputStream in) throws IOException {
        Map<String, Long> mallIds = new HashMap<>();
        jdbc.sql("SELECT id, name FROM malls").query((rs, i) -> {
            mallIds.put(rs.getString("name").trim().toLowerCase(), rs.getLong("id"));
            return null;
        }).list();

        int imported = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> c = parseCsvLine(line);
                if (lineNo == 1 && c.get(0).equalsIgnoreCase("mall_name")) {
                    continue; // header row
                }
                try {
                    if (c.size() < 4) {
                        throw new IllegalArgumentException("needs at least 4 columns");
                    }
                    Long mallId = mallIds.get(c.get(0).trim().toLowerCase());
                    if (mallId == null) {
                        throw new IllegalArgumentException("unknown mall '" + c.get(0) + "'");
                    }
                    createStore(mallId, parseFloor(c.get(1)), c.get(2), c.get(3),
                            c.size() > 4 ? c.get(4) : null, c.size() > 5 ? c.get(5) : null);
                    imported++;
                } catch (RuntimeException e) {
                    skipped++;
                    if (errors.size() < 20) {
                        errors.add("Line " + lineNo + ": " + e.getMessage());
                    }
                }
            }
        }
        return new ImportResult(imported, skipped, errors);
    }

    static int parseFloor(String raw) {
        String s = raw.trim();
        if (s.equalsIgnoreCase("g") || s.equalsIgnoreCase("ground")) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad floor '" + raw + "'");
        }
    }

    /** Minimal CSV parser: commas, with optional "double quoted" fields. */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    inQuotes = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
