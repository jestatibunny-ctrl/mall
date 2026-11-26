package com.mallhub.mall;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// Temporary controller to prove the stack works end to end on Day 1.
// Day 3 replaces this with proper DTOs, validation and role checks.
@RestController
@RequestMapping("/api/malls")
public class MallController {

    private final MallRepository repo;

    public MallController(MallRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Mall> all() {
        return repo.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mall create(@RequestBody Mall mall) {
        return repo.save(mall);
    }
}
