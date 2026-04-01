package com.velora.aijobflow.service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.email.from}")
    private String fromAddress;

    @Value("${app.email.from-name:Velora}")
    private String fromName;

    // ── DEBUG: confirm correct bean is loaded on startup ──────────────────────
    @PostConstruct
    public void init() {
        JavaMailSenderImpl impl = (JavaMailSenderImpl) mailSender;
        log.info(">>> MailSender class : {}", mailSender.getClass().getName());
        log.info(">>> MailSender host  : {}", impl.getHost());
        log.info(">>> MailSender port  : {}", impl.getPort());
    }

    // ── PUBLIC METHODS ────────────────────────────────────────────────────────

    public void sendWelcome(String toEmail, String userName) {
        String subject = "Welcome to Velora, " + first(userName) + " 🚀";
        send(toEmail, subject, welcomeHtml(userName, toEmail));
    }

    public void sendForgotPassword(String toEmail, String userName, String code) {
        String subject = "Velora Password Reset Request " ;
        send(toEmail, subject, forgotHtml(userName, code));
    }

    // ── PRIVATE SENDER ────────────────────────────────────────────────────────

    private void send(String to, String subject, String html) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setFrom(fromAddress, fromName);
            h.setTo(to);
            h.setSubject(subject);
            h.setText(html, true);
            mailSender.send(msg);
            log.info("Email sent → {} | {}", to, subject);
        } catch (Exception e) {
            log.error("Email failed → {} : {}", to, e.getMessage(), e);
        }
    }

    private String first(String name) {
        if (name == null || name.isBlank()) return "there";
        return name.trim().split("\\s+")[0];
    }

    // ── HTML TEMPLATES ────────────────────────────────────────────────────────

    private String welcomeHtml(String name, String email) {
        return page("Welcome to AI JobFlow 🚀",
            hero("#3b3fe4", "👋", "Welcome, " + first(name) + "!",
                 "Your AI job processing platform is ready."),
            "<p style='font-size:15px;color:#374151;line-height:1.7;margin:0 0 16px;'>" +
            "Hi <strong>" + name + "</strong>,</p>" +
            "<p style='font-size:15px;color:#374151;line-height:1.7;margin:0 0 20px;'>" +
            "You've successfully created your Velora account. " +
            "Start submitting AI jobs, monitor them in real time, and get powerful results.</p>" +

            "<table width='100%' cellpadding='0' cellspacing='0' style='margin:0 0 24px;'>" +
            row("⚡", "15+ AI Job Types", "Summarize, analyze, translate, review code & more") +
            row("🎯", "Priority Queue",  "CRITICAL / HIGH / MEDIUM / LOW routing") +
            row("🗓️", "Job Scheduling",  "Schedule jobs to run at any future time") +
            row("📊", "Live Monitoring", "Real-time status, logs & processing metrics") +
            "</table>" +

            "<p style='font-size:13px;color:#6b7280;margin:0;'>Registered as: " +
            "<strong style='color:#3b3fe4;'>" + email + "</strong></p>",

            btn("http://localhost:5173/create-job", "Create Your First Job →"),
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
            "We received a password reset request for your account. " +
            "Enter this code in the app to continue:</p>" +

            "<div style='background:#f3f4f6;border:2px dashed #d1d5db;border-radius:12px;" +
            "padding:28px;text-align:center;margin:0 0 12px;'>" +
            "<span style='font-family:\"Courier New\",monospace;font-size:40px;font-weight:900;" +
            "letter-spacing:14px;color:#111827;'>" + code + "</span>" +
            "</div>" +
            "<p style='font-size:13px;color:#6b7280;text-align:center;margin:0 0 24px;'>" +
            "⏱ Expires in <strong>15 minutes</strong></p>" +

            "<div style='background:#fffbeb;border:1px solid #fcd34d;border-radius:8px;" +
            "padding:12px 16px;font-size:13px;color:#92400e;'>" +
            "⚠️ Never share this code with anyone. Velora will never ask for it.</div>",

            "",
            "If you didn't request this, you can safely ignore this email."
        );
    }

    // ── BUILDING BLOCKS ───────────────────────────────────────────────────────

    private String page(String title, String heroBlock, String body, String cta, String footer) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
               "<meta name='viewport' content='width=device-width,initial-scale=1.0'>" +
               "<title>" + title + "</title></head>" +
               "<body style='margin:0;padding:0;background:#f3f4f6;" +
               "font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,sans-serif;'>" +
               "<table width='100%' cellpadding='0' cellspacing='0'><tr>" +
               "<td align='center' style='padding:40px 16px;'>" +
               "<table width='560' cellpadding='0' cellspacing='0' style='max-width:560px;width:100%;'>" +
               "<tr><td style='padding:0 0 24px;text-align:center;'>" +
               "<span style='font-size:28px;vertical-align:middle;'></span>" +
               "<span style='font-size:18px;font-weight:800;color:#111827;" +
               "letter-spacing:-0.5px;vertical-align:middle;margin-left:8px;'>Velora</span>" +
               "</td></tr>" +
               "<tr><td style='background:#fff;border-radius:16px;overflow:hidden;" +
               "box-shadow:0 4px 24px rgba(0,0,0,0.08);'>" +
               heroBlock +
               "<div style='padding:32px 36px;'>" + body + "</div>" +
               (cta.isEmpty() ? "" : "<div style='padding:0 36px 32px;text-align:center;'>" + cta + "</div>") +
               "</td></tr>" +
               "<tr><td style='padding:20px 0 0;text-align:center;" +
               "color:#9ca3af;font-size:12px;line-height:1.8;'>" +
               "<p style='margin:0;'>" + footer + "</p>" +
               "<p style='margin:4px 0 0;'>Velora &middot; " +
               "<a href='#' style='color:#9ca3af;'>Unsubscribe</a></p>" +
               "</td></tr>" +
               "</table></td></tr></table></body></html>";
    }

    private String hero(String color, String emoji, String title, String sub) {
        return "<div style='background:" + color + ";padding:36px;text-align:center;'>" +
               "<div style='font-size:42px;margin-bottom:12px;'>" + emoji + "</div>" +
               "<h1 style='margin:0 0 8px;color:#fff;font-size:22px;font-weight:800;" +
               "letter-spacing:-0.5px;'>" + title + "</h1>" +
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
        return "<tr><td style='padding:8px 0;vertical-align:top;width:36px;" +
               "font-size:20px;'>" + icon + "</td>" +
               "<td style='padding:8px 0 8px 12px;'>" +
               "<strong style='color:#111827;font-size:14px;'>" + title + "</strong><br>" +
               "<span style='color:#6b7280;font-size:13px;'>" + desc + "</span>" +
               "</td></tr>";
    }
}