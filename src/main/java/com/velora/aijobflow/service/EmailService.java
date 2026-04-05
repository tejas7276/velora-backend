package com.velora.aijobflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * EmailService — Resend API (HTTPS-based, Render-compatible).
 *
 * ROOT CAUSE FIX — Issue 4:
 * Render free tier BLOCKS outbound SMTP on ports 25, 465, 587.
 * JavaMailSender (SMTP) will always fail on Render free tier.
 * No code change can fix an infrastructure-level port block.
 *
 * SOLUTION: Resend.com API — sends emails via HTTPS (port 443).
 * Render allows all outbound HTTPS traffic.
 * Resend free tier: 100 emails/day, 3,000/month. No credit card.
 *
 * SETUP:
 * 1. Sign up at https://resend.com (free)
 * 2. Go to API Keys → Create API Key → copy the key
 * 3. Add to Render env vars: RESEND_API_KEY=re_xxxxxxxxxxxx
 * 4. Optional: add your domain for custom from-address
 *    Without domain: use onboarding@resend.dev (Resend's default sender)
 *
 * NOTE: @Async is preserved — email never blocks the HTTP request thread.
 * If Resend fails → logged as warning → registration still succeeds.
 */
@Slf4j
@Service
public class EmailService {

    // Resend API key — from Render env vars
    @Value("${RESEND_API_KEY:}")
    private String resendApiKey;

    // From address — must be verified on Resend OR use their default
    // If you haven't verified a domain yet, use: onboarding@resend.dev
    @Value("${app.email.from:onboarding@resend.dev}")
    private String fromAddress;

    @Value("${app.email.from-name:Velora}")
    private String fromName;

    @Value("${app.frontend.url:https://velora.onrender.com}")
    private String frontendUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── PUBLIC ────────────────────────────────────────────────────────────────

    public void sendWelcome(String toEmail, String userName) {
        send(toEmail,
             "Welcome to Velora, " + first(userName) + " 🚀",
             welcomeHtml(userName, toEmail));
    }

    public void sendForgotPassword(String toEmail, String userName, String code) {
        send(toEmail, "Velora Password Reset Request", forgotHtml(userName, code));
    }

    // ── PRIVATE ───────────────────────────────────────────────────────────────

    /**
     * @Async: runs in a background thread — HTTP request thread returns immediately.
     * Sends via Resend REST API (HTTPS port 443 — Render allows this).
     */
    @Async
    protected void send(String to, String subject, String html) {
        if (resendApiKey == null || resendApiKey.isBlank()) {
            log.warn("Email skipped — RESEND_API_KEY not configured. To: {} | Subject: {}", to, subject);
            return;
        }

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost request = new HttpPost("https://api.resend.com/emails");
            request.setHeader("Authorization", "Bearer " + resendApiKey);
            request.setHeader("Content-Type", "application/json");

            Map<String, Object> body = Map.of(
                "from",    fromName + " <" + fromAddress + ">",
                "to",      List.of(to),
                "subject", subject,
                "html",    html
            );

            request.setEntity(new StringEntity(
                mapper.writeValueAsString(body), StandardCharsets.UTF_8));

            String response = http.execute(request, r -> {
                byte[] bytes = r.getEntity().getContent().readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            });

            log.info("Email sent via Resend → {} | {} | response: {}", to, subject, response);

        } catch (Exception e) {
            // Email failure NEVER propagates to the API caller.
            log.error("Email failed (Resend) → {} | {} : {}", to, subject, e.getMessage());
        }
    }

    private String first(String name) {
        if (name == null || name.isBlank()) return "there";
        return name.trim().split("\\s+")[0];
    }

    // ── TEMPLATES ─────────────────────────────────────────────────────────────

    private String welcomeHtml(String name, String email) {
        return page("Welcome to Velora 🚀",
            hero("#3b3fe4", "👋", "Welcome, " + first(name) + "!",
                 "Your AI job processing platform is ready."),
            "<p style='font-size:15px;color:#374151;line-height:1.7;margin:0 0 16px;'>" +
            "Hi <strong>" + name + "</strong>,</p>" +
            "<p style='font-size:15px;color:#374151;line-height:1.7;margin:0 0 20px;'>" +
            "You've successfully created your Velora account. " +
            "Start submitting AI jobs and get powerful AI-driven results.</p>" +
            "<table width='100%' cellpadding='0' cellspacing='0' style='margin:0 0 24px;'>" +
            row("⚡", "15+ AI Job Types", "Summarize, analyze, translate, review code & more") +
            row("🎯", "Priority Queue",   "CRITICAL / HIGH / MEDIUM / LOW routing") +
            row("🗓️", "Job Scheduling",  "Schedule jobs to run at any future time") +
            row("📊", "Live Monitoring",  "Real-time status, logs & processing metrics") +
            "</table>" +
            "<p style='font-size:13px;color:#6b7280;margin:0;'>Registered as: " +
            "<strong style='color:#3b3fe4;'>" + email + "</strong></p>",
            btn(frontendUrl + "/create-job", "Create Your First Job →"),
            "Velora — Intelligent job processing at scale."
        );
    }

    private String forgotHtml(String name, String code) {
        return page("Password Reset Code",
            hero("#7c3aed", "🔐", "Reset Your Password",
                 "Use the one-time code below to reset your password."),
            "<p style='font-size:15px;color:#374151;line-height:1.7;margin:0 0 16px;'>" +
            "Hi <strong>" + first(name) + "</strong>,</p>" +
            "<p style='font-size:15px;color:#374151;line-height:1.7;margin:0 0 24px;'>" +
            "We received a password reset request. Enter this code to continue:</p>" +
            "<div style='background:#f3f4f6;border:2px dashed #d1d5db;border-radius:12px;" +
            "padding:28px;text-align:center;margin:0 0 12px;'>" +
            "<span style='font-family:monospace;font-size:40px;font-weight:900;" +
            "letter-spacing:14px;color:#111827;'>" + code + "</span></div>" +
            "<p style='font-size:13px;color:#6b7280;text-align:center;margin:0 0 24px;'>" +
            "⏱ Expires in <strong>15 minutes</strong></p>" +
            "<div style='background:#fffbeb;border:1px solid #fcd34d;border-radius:8px;" +
            "padding:12px 16px;font-size:13px;color:#92400e;'>" +
            "⚠️ Never share this code. Velora will never ask for it.</div>",
            "",
            "If you didn't request this, ignore this email."
        );
    }

    private String page(String title, String heroBlock, String body, String cta, String footer) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
               "<title>" + title + "</title></head>" +
               "<body style='margin:0;padding:0;background:#f3f4f6;font-family:sans-serif;'>" +
               "<table width='100%' cellpadding='0' cellspacing='0'><tr>" +
               "<td align='center' style='padding:40px 16px;'>" +
               "<table width='560' cellpadding='0' cellspacing='0'>" +
               "<tr><td style='padding:0 0 24px;text-align:center;'>" +
               "<span style='font-size:18px;font-weight:800;color:#111827;'>Velora</span></td></tr>" +
               "<tr><td style='background:#fff;border-radius:16px;overflow:hidden;" +
               "box-shadow:0 4px 24px rgba(0,0,0,0.08);'>" +
               heroBlock + "<div style='padding:32px 36px;'>" + body + "</div>" +
               (cta.isEmpty() ? "" :
                   "<div style='padding:0 36px 32px;text-align:center;'>" + cta + "</div>") +
               "</td></tr><tr><td style='padding:20px 0 0;text-align:center;" +
               "color:#9ca3af;font-size:12px;'><p>" + footer + "</p></td></tr>" +
               "</table></td></tr></table></body></html>";
    }

    private String hero(String color, String emoji, String title, String sub) {
        return "<div style='background:" + color + ";padding:36px;text-align:center;'>" +
               "<div style='font-size:42px;margin-bottom:12px;'>" + emoji + "</div>" +
               "<h1 style='margin:0 0 8px;color:#fff;font-size:22px;font-weight:800;'>" +
               title + "</h1>" +
               "<p style='margin:0;color:rgba(255,255,255,0.8);font-size:14px;'>" + sub + "</p>" +
               "</div>";
    }

    private String btn(String url, String label) {
        return "<a href='" + url + "' style='display:inline-block;" +
               "background:linear-gradient(135deg,#3b3fe4,#6366f1);" +
               "color:#fff;text-decoration:none;padding:14px 32px;border-radius:10px;" +
               "font-size:15px;font-weight:700;'>" + label + "</a>";
    }

    private String row(String icon, String title, String desc) {
        return "<tr><td style='padding:8px 0;vertical-align:top;width:36px;font-size:20px;'>" +
               icon + "</td><td style='padding:8px 0 8px 12px;'>" +
               "<strong style='color:#111827;font-size:14px;'>" + title + "</strong><br>" +
               "<span style='color:#6b7280;font-size:13px;'>" + desc + "</span></td></tr>";
    }
}