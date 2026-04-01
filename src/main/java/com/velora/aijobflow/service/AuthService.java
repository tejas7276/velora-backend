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

    private final UserRepository  userRepository;
    private final JwtUtil         jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final Optional<EmailService> emailService;

    private final Map<String, long[]> otpStore = new ConcurrentHashMap<>();

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException("Email already registered: " + request.getEmail());
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole("USER");

        User saved = userRepository.save(user);
        log.info("New user registered: {}", saved.getEmail());

        // ✅ FIXED
        emailService.ifPresent(s ->
                s.sendWelcome(saved.getEmail(), saved.getName())
        );

        String token = jwtUtil.generateToken(saved.getId(), saved.getEmail());
        return new AuthResponse(token, saved.getId(), saved.getName(), saved.getEmail());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
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
        userRepository.findByEmail(email).ifPresent(user -> {
            String code   = String.valueOf(100000 + new Random().nextInt(900000));
            long   expiry = System.currentTimeMillis() + 15 * 60 * 1000L;
            otpStore.put(email.toLowerCase(), new long[]{Long.parseLong(code), expiry});

            // ✅ FIXED
            emailService.ifPresent(s ->
                    s.sendForgotPassword(email, user.getName(), code)
            );

            log.info("OTP sent to {} (expires in 15 min)", email);
        });
    }

    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        String key    = email.toLowerCase();
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

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        otpStore.remove(key);
        log.info("Password reset successfully for {}", email);
    }
}