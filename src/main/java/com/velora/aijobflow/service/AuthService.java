package com.velora.aijobflow.service;

import com.velora.aijobflow.dto.AuthResponse;
import com.velora.aijobflow.dto.LoginRequest;
import com.velora.aijobflow.dto.RegisterRequest;
import com.velora.aijobflow.exception.DuplicateEmailException;
import com.velora.aijobflow.exception.InvalidCredentialsException;
import com.velora.aijobflow.model.User;
import com.velora.aijobflow.repository.UserRepository;
import com.velora.aijobflow.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository         userRepository;
    private final JwtUtil                jwtUtil;
    private final PasswordEncoder        passwordEncoder;
    private final Optional<EmailService> emailService;

    private final Map<String, long[]> otpStore = new ConcurrentHashMap<>();

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // NORMALIZE email to lowercase before any check or save.
        // Prevents "User@example.com" and "user@example.com" being treated
        // as different emails — both hit the unique constraint.
        String email = request.getEmail().trim().toLowerCase();

        // Optimistic check — fast path for obvious duplicates.
        // This does NOT prevent the race condition (two concurrent requests
        // can both pass this check). The DB unique constraint is the real guard.
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException("This email address is already registered.");
        }

        User user = new User();
        user.setName(request.getName().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole("USER");

        User saved;
        try {
            saved = userRepository.save(user);
            // Flush immediately so DataIntegrityViolationException is thrown HERE
            // (inside this try-catch) rather than later at transaction commit time
            // where it would be caught by the generic Exception handler.
            userRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // RACE CONDITION FIX: Two concurrent registrations with the same email
            // both passed the existsByEmail() check, then both attempted INSERT.
            // The second one hits the DB unique constraint here.
            // We catch it and convert to DuplicateEmailException → 409.
            log.warn("Duplicate email on save (race condition): {}", email);
            throw new DuplicateEmailException("This email address is already registered.");
        }

        log.info("New user registered: id={} email={}", saved.getId(), saved.getEmail());

        // emailService.ifPresent(s -> s.sendWelcome(saved.getEmail(), saved.getName()));

        String token = jwtUtil.generateToken(saved.getId(), saved.getEmail());
        return new AuthResponse(token, saved.getId(), saved.getName(), saved.getEmail());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Normalize email for login too — matches registered lowercase email
        String email = request.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }
        log.info("User logged in: {}", user.getEmail());
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getName(), user.getEmail());
    }

    @Transactional(readOnly = true)
    public void forgotPassword(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            String code   = String.valueOf(100000 + new Random().nextInt(900000));
            long   expiry = System.currentTimeMillis() + 15 * 60 * 1000L;
            otpStore.put(normalizedEmail, new long[]{Long.parseLong(code), expiry});

            emailService.ifPresent(s ->
                    s.sendForgotPassword(normalizedEmail, user.getName(), code));

            log.info("OTP sent to {} (expires in 15 min)", normalizedEmail);
        });
    }

    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        String key    = email.trim().toLowerCase();
        long[] stored = otpStore.get(key);

        if (stored == null) {
            throw new IllegalArgumentException("No reset request found for this email.");
        }
        if (System.currentTimeMillis() > stored[1]) {
            otpStore.remove(key);
            throw new IllegalArgumentException("Code expired. Please request a new one.");
        }
        if (Long.parseLong(code.trim()) != stored[0]) {
            throw new IllegalArgumentException("Invalid code. Please check and try again.");
        }

        User user = userRepository.findByEmail(key)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        otpStore.remove(key);
        log.info("Password reset successfully for {}", key);
    }
}