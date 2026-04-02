package com.velora.aijobflow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT Authentication Filter
 *
 * Runs once per request. Extracts and validates the JWT token from
 * the Authorization header, then sets the authentication in the
 * SecurityContext so Spring Security knows who is making the request.
 *
 * KEY FIX: shouldNotFilter() skips this filter entirely for public
 * endpoints (/auth/**). Without this, the filter runs on /auth/register,
 * finds no token, and Spring Security returns 403 — even though
 * SecurityConfig has .permitAll() for that path.
 *
 * Why? .permitAll() controls ACCESS, not whether filters run.
 * Filters always run unless explicitly skipped here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil JwtUtil;

    // These paths skip JWT validation entirely — no token needed
    private static final List<AntPathRequestMatcher> PUBLIC_PATHS = List.of(
        new AntPathRequestMatcher("/auth/**"),
        new AntPathRequestMatcher("/v3/api-docs/**"),
        new AntPathRequestMatcher("/swagger-ui/**"),
        new AntPathRequestMatcher("/swagger-ui.html"),
        new AntPathRequestMatcher("/actuator/**")
    );

    /**
     * Skip this filter for public endpoints.
     * This is the critical fix — without this, /auth/register gets a 403
     * because the filter runs, finds no Bearer token, and rejects the request.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.stream()
            .anyMatch(matcher -> matcher.matches(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String token = extractToken(request);

            if (StringUtils.hasText(token) && JwtUtil.validateToken(token)) {
                Long userId = JwtUtil.getUserIdFromToken(token);

                // Set authentication in SecurityContext
                // Principal = userId (Long) — SecurityUtils reads this
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, List.of());

                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("JWT valid — userId={} path={}", userId, request.getRequestURI());
            }

        } catch (Exception e) {
            // Invalid token — clear context, let Spring Security handle it
            SecurityContextHolder.clearContext();
            log.warn("JWT validation failed for path={} : {}", request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extracts Bearer token from Authorization header.
     * Returns null if header is missing or not a Bearer token.
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}