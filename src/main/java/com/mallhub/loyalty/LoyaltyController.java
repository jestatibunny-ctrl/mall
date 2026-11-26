package com.mallhub.loyalty;

import com.mallhub.catalog.StoreService;
import com.mallhub.user.UserService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class LoyaltyController {

    private final LoyaltyService loyalty;
    private final StoreService stores;
    private final UserService users;

    public LoyaltyController(LoyaltyService loyalty, StoreService stores, UserService users) {
        this.loyalty = loyalty;
        this.stores = stores;
        this.users = users;
    }

    @GetMapping("/loyalty")
    public String page(Principal principal, Model model) {
        long userId = users.require(principal.getName()).id();
        model.addAttribute("balance", loyalty.balance(userId));
        model.addAttribute("ledger", loyalty.ledger(userId));
        model.addAttribute("bills", loyalty.myBills(userId));
        model.addAttribute("stores", stores.search("", 0, 0));
        model.addAttribute("redeemKey", UUID.randomUUID().toString());
        model.addAttribute("rupeesPerPoint", LoyaltyService.RUPEES_PER_POINT);
        return "loyalty";
    }

    @PostMapping("/loyalty/bills")
    public String submit(Principal principal, @RequestParam long storeId, @RequestParam String billNo,
                         @RequestParam BigDecimal amount,
                         @RequestParam(value = "image", required = false) MultipartFile image,
                         RedirectAttributes flash) {
        try {
            loyalty.submitBill(users.require(principal.getName()).id(), storeId, billNo, amount, image);
            flash.addFlashAttribute("msg", "Bill submitted. Points are added once it is verified.");
        } catch (IllegalArgumentException | IOException e) {
            flash.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/loyalty";
    }

    @PostMapping("/loyalty/redeem")
    public String redeem(Principal principal, @RequestParam int points, @RequestParam String key,
                         RedirectAttributes flash) {
        try {
            boolean done = loyalty.redeem(users.require(principal.getName()).id(), points, key);
            flash.addFlashAttribute("msg", done ? "Redeemed " + points + " points" : "That request was already processed");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/loyalty";
    }

    // ---- admin side ----

    @GetMapping("/admin/bills")
    public String adminBills(Model model) {
        model.addAttribute("bills", loyalty.pendingBills());
        return "admin-bills";
    }

    @PostMapping("/admin/bills/{id}/verify")
    public String verify(@PathVariable long id, RedirectAttributes flash) {
        if (loyalty.verify(id)) {
            flash.addFlashAttribute("msg", "Bill verified and points credited");
        } else {
            flash.addFlashAttribute("err", "Bill was not pending");
        }
        return "redirect:/admin/bills";
    }

    @PostMapping("/admin/bills/{id}/reject")
    public String reject(@PathVariable long id, RedirectAttributes flash) {
        if (loyalty.reject(id)) {
            flash.addFlashAttribute("msg", "Bill rejected");
        } else {
            flash.addFlashAttribute("err", "Bill was not pending");
        }
        return "redirect:/admin/bills";
    }

    // Bill images are private: only admins (see SecurityConfig: /admin/**) can open them
    @GetMapping("/admin/files/{name:.+}")
    public ResponseEntity<Resource> file(@PathVariable String name) throws IOException {
        Path dir = loyalty.uploadPath();
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir) || !Files.isRegularFile(p)) {
            return ResponseEntity.notFound().build();
        }
        String type = Files.probeContentType(p);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, type == null ? "application/octet-stream" : type)
                .body(new FileSystemResource(p));
    }
}
