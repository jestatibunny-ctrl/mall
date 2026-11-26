package com.mallhub.user;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    public record UserRow(long id, String name, String email, String role, Long mallId) {}

    private final JdbcClient jdbc;

    public UserService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findByEmail(String email) {
        return jdbc.sql("SELECT id, name, email, role, mall_id FROM users WHERE lower(email) = lower(:e)")
                .param("e", email)
                .query(UserRow.class)
                .optional();
    }

    public UserRow require(String email) {
        return findByEmail(email).orElseThrow(() -> new IllegalStateException("Unknown user " + email));
    }
}
