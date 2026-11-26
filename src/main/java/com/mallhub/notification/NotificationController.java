package com.mallhub.notification;

import com.mallhub.user.UserService;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class NotificationController {

    private final NotificationService notifications;
    private final UserService users;

    public NotificationController(NotificationService notifications, UserService users) {
        this.notifications = notifications;
        this.users = users;
    }

    @GetMapping("/notifications")
    public String list(Principal principal, Model model) {
        long userId = users.require(principal.getName()).id();
        model.addAttribute("items", notifications.forUser(userId));
        notifications.markAllRead(userId);   // shown as unread this time, read from now on
        return "notifications";
    }
}
