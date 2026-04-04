package com.velora.aijobflow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    /**
     * ROOT CAUSE FIX:
     *
     * Render logs show: "Tomcat started on port 8080 with context path ''"
     * This means server.servlet.context-path=/api from application.properties
     * is NOT being applied — Tomcat sees an empty context path.
     *
     * Effect: A request to POST /api/auth/register arrives at the filter
     * as /api/auth/register — NOT as /auth/register.
     *
     * The original shouldNotFilter() only checked path.startsWith("/auth"),
     * which would NOT match /api/auth/register — so the JWT filter ran,
     * found no Bearer token, and the request was blocked → 403.
     *
     * Fix: Skip the filter for BOTH /auth/** and /api/auth/** paths.
     * This makes the filter safe regardless of whether context-path is
     * applied by Tomcat or not.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path   = request.getServletPath();
        String method = request.getMethod();

        // Always skip OPTIONS (CORS preflight)
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        // Skip auth endpoints — handle both context-path states:
        // With context-path=/api applied:    path = /auth/register     → matches /auth
        // With context-path='' (Render bug): path = /api/auth/register → matches /api/auth
        if (path.startsWith("/auth") || path.startsWith("/api/auth")) {
            return true;
        }

        // Skip API docs and health
        if (path.startsWith("/v3/api-docs") ||
            path.startsWith("/swagger")     ||
            path.startsWith("/actuator")) {
            return true;
        }

        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
                Long userId = jwtUtil.getUserIdFromToken(token);
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("JWT valid — userId={} path={}", userId, request.getRequestURI());
            }
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            log.warn("JWT validation failed: path={} error={}", request.getRequestURI(), e.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}