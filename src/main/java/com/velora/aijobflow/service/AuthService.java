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
        // Normalize email — prevent "User@x.com" vs "user@x.com" creating separate accounts
        String email = request.getEmail().trim().toLowerCase();

        // Fast-path check — avoids DB insert attempt for obvious duplicates
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
            // flush() forces the INSERT NOW (inside this try-catch) instead of
            // at transaction commit time. Without flush(), a duplicate email
            // race condition would throw DataIntegrityViolationException AFTER
            // this method returns — caught by the generic exception handler → 500.
            // With flush() here, we catch it and throw DuplicateEmailException → 409.
            userRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent registrations with the same email both passed the
            // existsByEmail() check. The second INSERT hits the DB unique constraint.
            log.warn("Duplicate email on save (concurrent request): {}", email);
            throw new DuplicateEmailException("This email address is already registered.");
        }

        log.info("New user registered: id={} email={}", saved.getId(), saved.getEmail());

        // Email is @Async in EmailService — this returns immediately.
        // Email failure NEVER reaches here. Register always succeeds after DB save.
        emailService.ifPresent(s -> s.sendWelcome(saved.getEmail(), saved.getName()));

        String token = jwtUtil.generateToken(saved.getId(), saved.getEmail());
        return new AuthResponse(token, saved.getId(), saved.getName(), saved.getEmail());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Normalize to match how email was stored at register time
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
        if (stored == null)
            throw new IllegalArgumentException("No reset request found for this email.");
        if (System.currentTimeMillis() > stored[1]) {
            otpStore.remove(key);
            throw new IllegalArgumentException("Code expired. Please request a new one.");
        }
        if (Long.parseLong(code.trim()) != stored[0])
            throw new IllegalArgumentException("Invalid code. Please check and try again.");

        User user = userRepository.findByEmail(key)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        otpStore.remove(key);
        log.info("Password reset successfully for {}", key);
    }
}