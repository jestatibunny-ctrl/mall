package com.mallhub.common;

import com.mallhub.notification.NotificationService;
import com.mallhub.user.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

// Puts the unread-notification count on every page so the navbar can show a badge.
@ControllerAdvice
public class GlobalModelAdvice {

    private final NotificationService notifications;
    private final UserService users;

    public GlobalModelAdvice(NotificationService notifications, UserService users) {
        this.notifications = notifications;
        this.users = users;
    }

    @ModelAttribute("unreadCount")
    public long unreadCount(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return 0L;
        }
        return users.findByEmail(auth.getName())
                .map(u -> notifications.unreadCount(u.id()))
                .orElse(0L);
    }
}
