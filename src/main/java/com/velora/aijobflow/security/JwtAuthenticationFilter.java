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

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // FIX: lowercase field name — uppercase "JwtUtil" broke @RequiredArgsConstructor
    private final JwtUtil jwtUtil;

    // FIX: paths must NOT include /api prefix.
    // server.servlet.context-path=/api means Spring strips /api before the filter sees the request.
    // So the incoming URL /api/auth/register becomes /auth/register inside the filter.
    // Using /api/auth/** here would NEVER match — causing 403 on every public endpoint.
    // private static final List<AntPathRequestMatcher> PUBLIC_PATHS = List.of(
    //     new AntPathRequestMatcher("/auth/**"),
    //     new AntPathRequestMatcher("/v3/api-docs/**"),
    //     new AntPathRequestMatcher("/swagger-ui/**"),
    //     new AntPathRequestMatcher("/swagger-ui.html"),
    //     new AntPathRequestMatcher("/actuator/**")
    // );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath(); // IMPORTANT: NOT getRequestURI()

    // Hard skip for all auth endpoints
    if (path.startsWith("/auth")) {
        return true;
    }

    // Optional: skip swagger & actuator
    if (path.startsWith("/v3/api-docs") ||
        path.startsWith("/swagger") ||
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