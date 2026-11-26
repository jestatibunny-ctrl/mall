package com.mallhub.offer;

import com.mallhub.catalog.StoreService;
import com.mallhub.user.UserService;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class OfferController {

    private final OfferService offers;
    private final StoreService stores;
    private final UserService users;

    public OfferController(OfferService offers, StoreService stores, UserService users) {
        this.offers = offers;
        this.stores = stores;
        this.users = users;
    }

    // Public: every approved offer that is valid today, across all malls
    @GetMapping("/offers")
    public String list(Model model) {
        model.addAttribute("offers", offers.active());
        return "offers";
    }

    @GetMapping("/admin/offers")
    public String admin(Principal principal, Model model) {
        var user = users.require(principal.getName());
        model.addAttribute("offers", offers.forAdmin(user));
        model.addAttribute("stores", stores.search("", 0, user.mallId() != null && "MALL_ADMIN".equals(user.role()) ? user.mallId() : 0));
        model.addAttribute("today", LocalDate.now());
        return "admin-offers";
    }

    @PostMapping("/admin/offers")
    public String create(Principal principal,
                         @RequestParam long storeId,
                         @RequestParam String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(defaultValue = "PERCENT") String discountType,
                         @RequestParam(defaultValue = "0") BigDecimal discountValue,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validTo,
                         RedirectAttributes flash) {
        try {
            offers.create(users.require(principal.getName()), storeId, title, description,
                    discountType, discountValue, validFrom, validTo);
            flash.addFlashAttribute("msg", "Offer submitted for approval");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/admin/offers";
    }

    @PostMapping("/admin/offers/{id}/approve")
    public String approve(@PathVariable long id, RedirectAttributes flash) {
        if (offers.approve(id)) {
            flash.addFlashAttribute("msg", "Offer approved and users notified");
        } else {
            flash.addFlashAttribute("err", "Offer was not pending");
        }
        return "redirect:/admin/offers";
    }

    @PostMapping("/admin/offers/{id}/reject")
    public String reject(@PathVariable long id, RedirectAttributes flash) {
        if (offers.reject(id)) {
            flash.addFlashAttribute("msg", "Offer rejected");
        } else {
            flash.addFlashAttribute("err", "Offer was not pending");
        }
        return "redirect:/admin/offers";
    }
}
