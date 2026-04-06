package com.velora.aijobflow.controller;

import com.velora.aijobflow.model.User;
import com.velora.aijobflow.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/users")
    public ResponseEntity<?> getUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            Principal principal
    ) {

        // 🔒 SECURITY CHECK (ONLY YOUR EMAIL ALLOWED)
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "status", "error",
                    "message", "Unauthorized - Please login"
            ));
        }

        String email = principal.getName();

        if (email == null || !email.equalsIgnoreCase("tejasshinde7276@gmail.com")) {
            return ResponseEntity.status(403).body(Map.of(
                    "status", "error",
                    "message", "Access Denied - Not authorized"
            ));
        }

        // 📊 SORTING
        Sort sort = order.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page - 1, limit, sort);

        Page<User> userPage;

        // 🔍 SEARCH
        if (search != null && !search.trim().isEmpty()) {
            userPage = userRepository
                    .findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                            search.trim(), search.trim(), pageable
                    );
        } else {
            userPage = userRepository.findAll(pageable);
        }

        // 📦 RESPONSE
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("count", userPage.getTotalElements());
        response.put("page", page);
        response.put("totalPages", userPage.getTotalPages());
        response.put("data", userPage.getContent());

        return ResponseEntity.ok(response);
    }
}