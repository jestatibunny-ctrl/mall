package com.mallhub.admin;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminController {

    public record Totals(long malls, long stores, long users, long activeOffers, long pendingOffers, long pendingBills) {}

    public record MallStat(long id, String name, long stores, long offers,
                           long freeSlots, long totalSlots, long footfall) {}

    private final JdbcClient jdbc;

    public AdminController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin")
    public String dashboard(Model model) {
        Totals totals = jdbc.sql("""
                SELECT (SELECT count(*) FROM malls) AS malls,
                       (SELECT count(*) FROM stores) AS stores,
                       (SELECT count(*) FROM users) AS users,
                       (SELECT count(*) FROM offers WHERE status = 'APPROVED'
                           AND CURRENT_DATE BETWEEN valid_from AND valid_to) AS active_offers,
                       (SELECT count(*) FROM offers WHERE status = 'PENDING') AS pending_offers,
                       (SELECT count(*) FROM bills WHERE status = 'PENDING') AS pending_bills
                """).query(Totals.class).single();

        List<MallStat> stats = jdbc.sql("""
                SELECT m.id, m.name,
                       (SELECT count(*) FROM stores s WHERE s.mall_id = m.id) AS stores,
                       (SELECT count(*) FROM offers o JOIN stores s ON s.id = o.store_id
                         WHERE s.mall_id = m.id AND o.status = 'APPROVED'
                           AND CURRENT_DATE BETWEEN o.valid_from AND o.valid_to) AS offers,
                       COALESCE((SELECT sum(ps.available_slots) FROM parking_status ps
                                   JOIN parking_zones z ON z.id = ps.zone_id WHERE z.mall_id = m.id), 0) AS free_slots,
                       COALESCE((SELECT sum(z.total_slots) FROM parking_zones z WHERE z.mall_id = m.id), 0) AS total_slots,
                       COALESCE((SELECT sum(f.count) FROM footfall_events f
                                  WHERE f.mall_id = m.id AND f.ts > now() - interval '24 hours'), 0) AS footfall
                  FROM malls m ORDER BY m.name
                """).query(MallStat.class).list();

        model.addAttribute("totals", totals);
        model.addAttribute("stats", stats);
        model.addAttribute("labels", stats.stream().map(MallStat::name).toList());
        model.addAttribute("footfallData", stats.stream().map(MallStat::footfall).toList());
        return "dashboard";
    }
}
