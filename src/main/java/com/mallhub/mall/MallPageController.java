package com.mallhub.mall;

import com.mallhub.catalog.StoreService;
import com.mallhub.common.GeoUtil;
import com.mallhub.offer.OfferService;
import com.mallhub.parking.ParkingService;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

// Server-rendered website pages. The JSON API stays in MallController (/api/malls).
@Controller
public class MallPageController {

    public record NearbyMall(Mall mall, double km) {}

    private final MallRepository repo;
    private final StoreService stores;
    private final OfferService offers;
    private final ParkingService parking;

    public MallPageController(MallRepository repo, StoreService stores, OfferService offers, ParkingService parking) {
        this.repo = repo;
        this.stores = stores;
        this.offers = offers;
        this.parking = parking;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("malls", repo.findAll());
        return "index";
    }

    @GetMapping("/malls/new")
    public String newMallForm(Model model) {
        model.addAttribute("mall", new Mall());
        return "mall-form";
    }

    @PostMapping("/malls")
    public String createMall(@ModelAttribute Mall mall) {
        Mall saved = repo.save(mall);
        return "redirect:/malls/" + saved.getId();
    }

    // Malls within a radius of the visitor, nearest first (Haversine distance)
    @GetMapping("/malls/nearby")
    public String nearby(@RequestParam double lat, @RequestParam double lng,
                         @RequestParam(defaultValue = "15") double radius, Model model) {
        List<NearbyMall> result = repo.findAll().stream()
                .map(m -> new NearbyMall(m, GeoUtil.distanceKm(lat, lng, m.getLatitude(), m.getLongitude())))
                .filter(n -> n.km() <= radius)
                .sorted(Comparator.comparingDouble(NearbyMall::km))
                .toList();
        model.addAttribute("results", result);
        model.addAttribute("radius", radius);
        return "nearby";
    }

    @GetMapping("/malls/{id}")
    public String mallDetail(@PathVariable Long id, Model model) {
        Mall mall = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mall not found"));
        model.addAttribute("mall", mall);
        model.addAttribute("stores", stores.search("", 0, id));
        model.addAttribute("offers", offers.active().stream().filter(o -> o.mallId() == id).toList());
        model.addAttribute("zones", parking.zones().stream().filter(z -> z.mallId() == id).toList());
        return "mall-detail";
    }
}
