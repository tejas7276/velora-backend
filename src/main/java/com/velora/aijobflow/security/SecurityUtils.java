package com.velora.aijobflow.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityUtils — Extract userId safely from JWT
 */
public class SecurityUtils {

    private SecurityUtils() {}

    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new SecurityException("No authenticated user found in security context");
        }

        Object principal = auth.getPrincipal();

        // ✅ CASE 1: already Long (ideal case)
        if (principal instanceof Long) {
            return (Long) principal;
        }

        // ✅ CASE 2: String (could be userId OR email)
        if (principal instanceof String str) {

            // ❌ If it's email → don't crash, give clear error
            if (str.contains("@")) {
                throw new SecurityException(
                    "JWT contains EMAIL instead of userId. Fix JwtUtil to store userId. Current value: " + str
                );
            }

            try {
                return Long.parseLong(str);
            } catch (NumberFormatException e) {
                throw new SecurityException("Invalid userId in JWT: " + str);
            }
        }

        // ✅ CASE 3: UserDetails
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails ud) {
            String username = ud.getUsername();

            if (username.contains("@")) {
                throw new SecurityException(
                    "JWT contains EMAIL instead of userId. Fix JwtUtil. Current value: " + username
                );
            }

            try {
                return Long.parseLong(username);
            } catch (NumberFormatException e) {
                throw new SecurityException("Invalid userId in UserDetails: " + username);
            }
        }

        throw new SecurityException("Unknown principal type: " + principal.getClass().getName());
    }

    public static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }
}