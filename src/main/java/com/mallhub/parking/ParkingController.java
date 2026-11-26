package com.mallhub.parking;

import com.mallhub.user.UserService;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ParkingController {

    private final ParkingService parking;
    private final ParkingBroadcaster broadcaster;
    private final UserService users;

    public ParkingController(ParkingService parking, ParkingBroadcaster broadcaster, UserService users) {
        this.parking = parking;
        this.broadcaster = broadcaster;
        this.users = users;
    }

    @GetMapping("/parking")
    public String page(Principal principal, Model model) {
        model.addAttribute("zones", parking.zones());
        if (principal != null) {
            model.addAttribute("bookings", parking.myBookings(users.require(principal.getName()).id()));
        }
        return "parking";
    }

    @PostMapping("/parking/book")
    public String book(Principal principal, @RequestParam long zoneId,
                       @RequestParam(defaultValue = "2") int hours, RedirectAttributes flash) {
        try {
            var r = parking.book(users.require(principal.getName()).id(), zoneId, hours);
            flash.addFlashAttribute("msg", "Booked! " + r.mallName() + ", " + r.zoneName()
                    + ", slot " + r.slotNo() + " - see 'Find my car' below.");
            broadcaster.broadcast();
        } catch (ParkingFullException | IllegalStateException | IllegalArgumentException e) {
            flash.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/parking";
    }

    @PostMapping("/parking/{id}/cancel")
    public String cancel(Principal principal, @PathVariable long id, RedirectAttributes flash) {
        if (parking.cancel(users.require(principal.getName()).id(), id)) {
            flash.addFlashAttribute("msg", "Booking cancelled");
            broadcaster.broadcast();
        } else {
            flash.addFlashAttribute("err", "Booking not found or already finished");
        }
        return "redirect:/parking";
    }
}
