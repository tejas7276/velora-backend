package com.velora.aijobflow.controller;

import com.velora.aijobflow.dto.AuthResponse;
import com.velora.aijobflow.dto.LoginRequest;
import com.velora.aijobflow.dto.RegisterRequest;
import com.velora.aijobflow.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    // POST /api/auth/forgot-password?email=user@example.com
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestParam String email) {
        authService.forgotPassword(email);
        return ResponseEntity.ok(Map.of(
            "message", "If this email is registered, a reset code has been sent."
        ));
    }

    // POST /api/auth/reset-password?email=...&code=123456&newPassword=...
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @RequestParam String email,
            @RequestParam String code,
            @RequestParam String newPassword) {
        authService.resetPassword(email, code, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully."));
    }
}