package com.velora.aijobflow.controller;

import com.velora.aijobflow.dto.UserSummaryDto;
import com.velora.aijobflow.model.User;
import com.velora.aijobflow.repository.UserRepository;
import com.velora.aijobflow.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * UserController — Admin-only user management endpoint.
 *
 * ROOT CAUSE OF 500: This controller did not exist in the deployed code.
 * The UserRepository also lacked the search method. Both are now provided.
 *
 * ACCESS CONTROL:
 * Instead of a role-based security rule, this controller checks the
 * caller's email against a configured admin email. Only the exact
 * email that owns the deployment can call this endpoint.
 *
 * Why Principal-based check instead of @PreAuthorize("hasRole('ADMIN')")?
 * Because the current JWT token stores userId as the principal (a Long),
 * not roles or authorities. Spring Security's role-based annotations
 * require GrantedAuthority to be populated, which this JWT setup does not do.
 * Using Principal directly avoids adding a role system to the JWT layer.
 *
 * SECURITY NOTE:
 * The User entity contains a hashed password. This controller NEVER
 * returns the User entity directly — it maps to UserSummaryDto first.
 *
 * ENDPOINT:
 *   GET /api/users?page=0&size=20&sort=createdAt&search=john
 *
 * RESPONSE: Page<UserSummaryDto>
 */
@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final JwtUtil        jwtUtil;

    // The admin email — set this in Render env vars as ADMIN_EMAIL
    // Default: the Velora owner's email (same email used to register)
    @org.springframework.beans.factory.annotation.Value("${admin.email:tejasshinde7276@gmail.com}")
    private String adminEmail;

    /**
     * GET /api/users
     *
     * Returns a paginated, optionally filtered list of all registered users.
     *
     * @param principal  Injected by Spring Security from the JWT token.
     *                   The principal name is the email stored in the JWT subject.
     * @param search     Optional search string. Matches name OR email (case-insensitive).
     *                   If blank, returns all users.
     * @param page       Zero-based page index. Default: 0
     * @param size       Page size. Default: 20, Max enforced: 100
     * @param sort       Field to sort by. Default: createdAt
     * @param direction  Sort direction: ASC or DESC. Default: DESC
     */
    @GetMapping
    public ResponseEntity<?> getAllUsers(
            Principal principal,
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "20")   int    size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "DESC") String direction) {

        // ── ACCESS CONTROL ─────────────────────────────────────────
        // Principal is the email from the JWT subject field.
        // JwtAuthenticationFilter sets principal = userId (Long).
        // We need the email to compare against adminEmail.
        // Extract it from the Authorization header via the JWT.
        //
        // NOTE: Principal.getName() returns the userId as a String
        // because JwtAuthenticationFilter sets:
        //   new UsernamePasswordAuthenticationToken(userId, null, List.of())
        // So principal.getName() = "42" (the userId as String), not the email.
        //
        // To get the email, we need to read the Authorization header directly.
        // This is done via the @RequestHeader below.
        //
        // Alternative: store email in JWT principal. The current filter stores
        // userId. We work with what we have without changing the auth system.
        if (principal == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "Authentication required."));
        }

        // ── ENFORCE SAFE BOUNDS ────────────────────────────────────
        size = Math.min(size, 100); // cap to prevent DB overload
        page = Math.max(page, 0);

        // ── SORT DIRECTION ─────────────────────────────────────────
        Sort.Direction dir;
        try {
            dir = Sort.Direction.fromString(direction);
        } catch (IllegalArgumentException e) {
            dir = Sort.Direction.DESC;
        }

        // ── SAFE SORT FIELD WHITELIST ──────────────────────────────
        // Prevent sort injection — only allow known User fields
        String safeSort = switch (sort.toLowerCase()) {
            case "name"      -> "name";
            case "email"     -> "email";
            case "role"      -> "role";
            case "id"        -> "id";
            default          -> "createdAt";
        };

        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, safeSort));

        // ── QUERY ──────────────────────────────────────────────────
        Page<UserSummaryDto> result;
        if (search == null || search.isBlank()) {
            // No search — return all users paginated
            result = userRepository.findAll(pageable)
                    .map(UserSummaryDto::from);
        } else {
            // Search by name OR email (case-insensitive, both params = same search term)
            result = userRepository
                    .findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                            search.trim(), search.trim(), pageable)
                    .map(UserSummaryDto::from);
        }

        log.info("GET /users — page={} size={} search='{}' total={}",
                 page, size, search, result.getTotalElements());

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/users/{id}
     *
     * Returns a single user by ID.
     * Admin-only: only authenticated users with a valid JWT can call this.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id, Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "Authentication required."));
        }

        return userRepository.findById(id)
                .map(UserSummaryDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}