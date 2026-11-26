package com.mallhub.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Creates the first super admin. The password comes from MALLHUB_ADMIN_PASSWORD (default admin123 is for
// local use only: always set the variable on a public deployment).
@Component
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    private final String adminPassword;

    public AdminSeeder(UserRepository users, PasswordEncoder encoder,
                       @Value("${mallhub.admin.password:admin123}") String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        String email = "admin@mallhub.com";
        if (!users.existsByEmailIgnoreCase(email)) {
            AppUser admin = new AppUser();
            admin.setName("Super Admin");
            admin.setEmail(email);
            admin.setPasswordHash(encoder.encode(adminPassword));
            admin.setRole(Role.SUPER_ADMIN);
            users.save(admin);
        }
    }
}
