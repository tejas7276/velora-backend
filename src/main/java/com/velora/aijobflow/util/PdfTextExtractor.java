package com.velora.aijobflow.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

/**
 * Extracts and structures plain text from PDF files.
 *
 * FIX: The original file had two class definitions concatenated together
 * (the PdfTextExtractor class immediately followed by the Job model class
 * without a package separator) — Java compile error.
 * This file contains only PdfTextExtractor.
 *
 * structureText() adds [SECTION: ...] markers around resume headings
 * so chunk scoring can identify and boost section-relevant chunks.
 */
@Slf4j
@Component
public class PdfTextExtractor {

    private static final int MIN_TEXT_LENGTH = 20;

    /** Extract from uploaded MultipartFile. Called in JobController. */
    public String extract(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty or null");
        }
        String name = file.getOriginalFilename();
        // Non-PDF: return raw bytes as string
        if (name != null && !name.toLowerCase().endsWith(".pdf")) {
            return new String(file.getBytes()).trim();
        }
        try (PDDocument doc = PDDocument.load(file.getInputStream())) {
            return extractAndStructure(doc, name != null ? name : "upload");
        }
    }

    /** Extract from saved file path. Called in JobWorkerConsumer fallback. */
    public String extract(String filePath) throws IOException {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path is null or blank");
        }
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }
        if (!filePath.toLowerCase().endsWith(".pdf")) {
            return new String(java.nio.file.Files.readAllBytes(file.toPath())).trim();
        }
        try (PDDocument doc = PDDocument.load(file)) {
            return extractAndStructure(doc, filePath);
        }
    }

    /** Extract from raw bytes. */
    public String extract(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Byte array is empty");
        }
        try (PDDocument doc = PDDocument.load(bytes)) {
            return extractAndStructure(doc, "byte[]");
        }
    }

    // ── INTERNAL ──────────────────────────────────────────────────────────────

    private String extractAndStructure(PDDocument doc, String source) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        String text = stripper.getText(doc);

        if (text == null || text.trim().length() < MIN_TEXT_LENGTH) {
            log.warn("PDF extraction returned minimal text from '{}' — may be image-only", source);
            throw new IOException(
                "PDF has no extractable text (possibly scanned). " +
                "Provide a text-based PDF or paste content manually.");
        }

        String structured = structureText(text.trim());
        log.info("PDF extracted: {} chars from '{}'", structured.length(), source);
        return structured;
    }

    /**
     * Adds [SECTION: ...] markers before known resume section headers.
     * This improves chunk scoring in DocumentQAEngine — section-matching
     * chunks get a header boost and are more likely to be selected.
     */
    private String structureText(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        String[] headers = {
            "EXPERIENCE", "WORK EXPERIENCE", "PROFESSIONAL EXPERIENCE",
            "EDUCATION", "ACADEMIC", "QUALIFICATION",
            "SKILLS", "TECHNICAL SKILLS", "CORE COMPETENCIES",
            "PROJECTS", "SUMMARY", "OBJECTIVE", "PROFILE",
            "CONTACT", "PERSONAL INFORMATION", "PERSONAL DETAILS",
            "CERTIFICATIONS", "ACHIEVEMENTS", "AWARDS",
            "LANGUAGES", "INTERESTS", "REFERENCES"
        };

        String result = raw;
        for (String header : headers) {
            // Only insert marker if the header appears as its own line (not mid-sentence)
            result = result.replaceAll(
                "(?im)^(" + header.replace(" ", "\\\\s+") + "\\s*[:\\-]?)\\s*$",
                "\n[SECTION: $1]\n"
            );
        }

        return result
            .replaceAll("\\r\\n", "\n")
            .replaceAll("\\r", "\n")
            .replaceAll("\\n{4,}", "\n\n")
            .replaceAll("[ \\t]{2,}", " ")
            .trim();
    }
}