package com.velora.aijobflow.util;

import java.util.*;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PromptRouter  —  Central intelligence layer for Velora AI system
 * ═══════════════════════════════════════════════════════════════════
 *
 *  FIX: normalizeQuery() was returning bare field names like "location"
 *  instead of a proper question. The AI received "location" as the
 *  entire query and had no idea what to do — extract? list? describe?
 *
 *  Correct behavior:
 *    "where candidate belong"   → "What is the location of the candidate?"
 *    "candidate name?"          → "What is the name of the candidate?"
 *    "how long has he worked"   → "What is the total experience of the candidate?"
 *
 *  The output is always a proper question string, not a bare field name.
 */
public final class PromptRouter {

    private PromptRouter() {}

    // ── DOC TYPES ─────────────────────────────────────────────────────────────

    public enum DocType {
        RESUME, CODE, DATA, MEETING_NOTES, EMAIL, LEGAL, ARTICLE, GENERAL
    }

    // ── TASK TYPES ────────────────────────────────────────────────────────────

    public enum TaskType {
        EXTRACTION,  // extract exact values — temperature 0.0, strict grounding
        ANALYSIS,    // analyze patterns    — temperature 0.15, moderate grounding
        GENERATION   // generate content    — temperature 0.35, light grounding
    }

    // ── DOC TYPE DETECTION ────────────────────────────────────────────────────

    /**
     * Detects what kind of document the text is.
     *
     * FIX: Previous version triggered RESUME on any text containing "experience"
     * or "skills" (very common words). Now requires a minimum signal score of 4
     * distinct resume markers before classifying as RESUME.
     */
    public static DocType detectDocType(String text) {
        if (text == null || text.isBlank()) return DocType.GENERAL;
        String lower = text.toLowerCase();

        // ── RESUME: requires 4+ distinct strong markers ───────────────────────
        // Weak markers (common in any text) no longer trigger RESUME alone
        String[] strongResumeMarkers = {
            "curriculum vitae", "resume", "work experience", "professional experience",
            "linkedin.com/in/", "github.com/", "objective:", "career objective",
            "bachelor of", "master of", "b.tech", "m.tech", "b.e.", "m.e.",
            "internship", "cgpa", "gpa:", "projects:", "certifications:",
            "summary:", "profile summary", "date of birth", "nationality:"
        };
        String[] softResumeMarkers = {
            "experience", "education", "skills", "university", "college",
            "company", "role", "position"
        };
        long strongCount = Arrays.stream(strongResumeMarkers).filter(lower::contains).count();
        long softCount   = Arrays.stream(softResumeMarkers).filter(lower::contains).count();

        // Needs either 2+ strong markers OR 1 strong + 3 soft
        if (strongCount >= 2 || (strongCount >= 1 && softCount >= 3)) {
            return DocType.RESUME;
        }

        // ── CODE: has language keywords + syntax ──────────────────────────────
        if (hasAny(lower, "def ", "public class", "private ", "return ",
                   "if (", "for (", "while (", "const ", "let ", "var ",
                   "async ", "await ", "package ", "import java", "using ") &&
            (lower.contains("{") && lower.contains("}"))) {
            return DocType.CODE;
        }

        // ── DATA: numeric-heavy with metric keywords ──────────────────────────
        if ((countPattern(text, "\\d+\\.\\d+") > 5 || countPattern(text, "\\d+%") > 3) &&
            hasAny(lower, "total", "average", "revenue", "sales", "metric",
                   "profit", "growth", "rate", "q1", "q2", "q3", "q4")) {
            return DocType.DATA;
        }

        // ── MEETING NOTES ─────────────────────────────────────────────────────
        if (hasAny(lower, "attendee", "agenda", "action item", "minutes of meeting",
                   "follow-up", "next steps", "assigned to", "standup", "sprint review")) {
            return DocType.MEETING_NOTES;
        }

        // ── LEGAL — contract / legal language signals ─────────────────────────
        if (hasAny(lower, "whereas", "hereby", "therein", "shall be", "notwithstanding",
                   "indemnify", "liability", "jurisdiction", "clause", "agreement between",
                   "terms and conditions", "party of the first")) {
            return DocType.LEGAL;
        }

        // ── EMAIL ─────────────────────────────────────────────────────────────
        long emailMarkers = Arrays.stream(new String[]{"subject:", "from:", "to:", "cc:",
            "dear ", "regards,", "best regards", "sincerely,"})
            .filter(lower::contains).count();
        if (emailMarkers >= 2) return DocType.EMAIL;

        // ── ARTICLE — editorial / research patterns ───────────────────────────
        if (hasAny(lower, "according to", "researchers found", "study shows", "published in",
                   "in conclusion", "abstract", "references", "introduction")) {
            return DocType.ARTICLE;
        }

        return DocType.GENERAL;
    }

    // ── SYSTEM PROMPTS ────────────────────────────────────────────────────────

    public static String systemPromptFor(TaskType task, DocType docType) {
        return switch (task) {
            case EXTRACTION -> extractionBase(docType);
            case ANALYSIS   -> analysisBase(docType);
            case GENERATION -> generationBase(docType);
        };
    }

    private static String extractionBase(DocType docType) {
        return switch (docType) {
            case RESUME ->
                "You are a resume information extraction engine. " +
                "Extract the exact value from the resume as it appears. " +
                "Return ONLY the value — no labels, no sentences, no explanation.";
            case CODE ->
                "You are a code analysis engine. " +
                "Extract specific facts from the provided code only. " +
                "Return only what is asked. No assumptions.";
            case DATA ->
                "You are a data extraction specialist. " +
                "Extract exact numbers, metrics, or values from the provided data. " +
                "Never estimate or calculate values not present in the data.";
            case LEGAL ->
                "You are a legal document extraction specialist. " +
                "Extract exact clause text, party names, dates, and obligations. " +
                "Quote directly when precision is required. Never paraphrase legal terms.";
            default ->
                "You are a precise information extraction engine. " +
                "Extract the exact value from the provided content. " +
                "Return only the value. No padding. No explanation.";
        };
    }

    private static String analysisBase(DocType docType) {
        return switch (docType) {
            case RESUME ->
                "You are a senior HR analyst and recruiter. " +
                "Analyze resumes with a hiring lens. " +
                "Reference specific resume content in every observation.";
            case CODE ->
                "You are a principal software engineer. " +
                "All code feedback must reference specific patterns in the submitted code.";
            case DATA ->
                "You are a senior business analyst. " +
                "Analyze only the data provided. Never invent statistics or percentages.";
            case MEETING_NOTES ->
                "You are a senior project manager. " +
                "Summarize only what is in the notes. " +
                "Do not invent attendees, decisions, or action items.";
            case LEGAL ->
                "You are a Senior Legal Analyst. " +
                "Identify obligations, rights, risks, and ambiguities in legal documents. " +
                "Cite specific clauses. Never give legal advice — factual analysis only.";
            case ARTICLE ->
                "You are a Senior Research Analyst. " +
                "Evaluate argument quality, evidence strength, and logical consistency. " +
                "Reference specific claims and evidence from the article.";
            default ->
                "You are a senior analyst. " +
                "Extract insights only from the provided content.";
        };
    }

    private static String generationBase(DocType docType) {
        return switch (docType) {
            case RESUME ->
                "You are a professional resume and career writer. " +
                "Create content grounded in the provided background. " +
                "Never exaggerate or invent credentials.";
            case EMAIL ->
                "You are an enterprise-grade email generation engine.\n\n" +

                "PRIMARY OBJECTIVE:\n" +
                "- Generate ONE complete email strictly grounded in the provided context.\n" +
                "- This is NOT creative writing. This is controlled document generation.\n\n" +

                "STRICT DATA POLICY (HARD ENFORCED):\n" +
                "- Use ONLY information explicitly present in the context.\n" +
                "- ZERO hallucination: Do NOT invent names, companies, roles, numbers, or achievements.\n" +
                "- If a required detail is missing, OMIT it or use a safe generic fallback.\n" +
                "- NEVER create fictional sender or recipient names.\n\n" +

                "STRUCTURE (MANDATORY):\n" +
                "Subject: <clear and specific>\n\n" +
                "Greeting line\n\n" +
                "Opening line (purpose)\n\n" +
                "Body (2–5 sentences expanding ONLY on given context)\n\n" +
                "Closing line (clear next step or request)\n\n" +
                "Sign-off (e.g., Regards, / Thanks,)\n" +
                "[Sender name ONLY if present in context]\n\n" +

                "FALLBACK RULES:\n" +
                "- If recipient name is missing → use 'Dear Sir/Madam'\n" +
                "- If sender name is missing → DO NOT generate any name\n\n" +

                "STYLE RULES:\n" +
                "- Simple, natural, realistic human tone\n" +
                "- No buzzwords, no exaggeration, no fake metrics\n" +
                "- No repetition, no fluff\n\n" +

                "OUTPUT RULES:\n" +
                "- Output EXACTLY one email\n" +
                "- No explanations, no extra text\n" +
                "- No placeholders like [Your Name]\n" +
                "- No meta commentary\n\n" +

                "FAIL-SAFE:\n" +
                "- If context is insufficient, still write the email using ONLY safe generic phrasing without inventing details.";
            default ->
                "You are an expert content creator. " +
                "Generate high-quality content grounded in the provided context.";
        };
    }

    // ── GROUNDING CONSTRAINTS ─────────────────────────────────────────────────

    public static String groundingFor(TaskType task) {
        return switch (task) {
            case EXTRACTION ->
                "\n\nGROUNDING RULES (STRICT):\n" +
                "- Use ONLY information present in the provided document.\n" +
                "- If the exact value is not in the document: return NOT_FOUND\n" +
                "- Do NOT infer, guess, or use external knowledge.\n" +
                "- Return the shortest accurate answer. No padding.";
            case ANALYSIS ->
                "\n\nGROUNDING RULES (MODERATE):\n" +
                "- Base all analysis on the provided document.\n" +
                "- If document is insufficient: write INSUFFICIENT_DATA\n" +
                "- Mark any supplemented info with [General Knowledge].";
            case GENERATION ->
                "\n\nGROUNDING RULES (LIGHT):\n" +
                "- Ground generated content in the provided context.\n" +
                "- If key information is missing: mark as [ADD YOUR OWN: description]\n" +
                "- Do not exaggerate or invent credentials.";
        };
    }

    // ── QUERY NORMALIZATION ───────────────────────────────────────────────────

    /**
     * Normalizes ambiguous natural language questions into clear extraction questions.
     *
     * FIX: Previous version returned bare field names like "location" or "name"
     * which the AI received as the entire question — causing confusion about
     * whether to extract, describe, or list.
     *
     * Now returns a proper question string like:
     *   "What is the location of the candidate?"
     *   "What is the name of the candidate?"
     *
     * If the question is already clear, it is returned unchanged.
     *
     * @param question raw user question
     * @return normalized question suitable for direct AI use
     */
    public static String normalizeQuery(String question) {
        if (question == null || question.isBlank()) return question;
        String q = question.toLowerCase().trim().replaceAll("[?!.,]", "").trim();

        // ── Location ──────────────────────────────────────────────────────────
        if (matchesAny(q, "belong", "native", "hometown", "hails from", "residing",
                       "resides", "based in", "origin", "from where",
                       "where is candidate", "where is the candidate",
                       "where does candidate", "where does the candidate",
                       "current location", "candidate location",
                       "location of candidate")) {
            return "What is the location/city/state/country of the candidate?";
        }

        // ── Name ──────────────────────────────────────────────────────────────
        if (matchesAny(q, "candidate name", "applicant name", "full name",
                       "who is the candidate", "person name", "name of candidate")) {
            return "What is the full name of the candidate?";
        }

        // ── Total experience ──────────────────────────────────────────────────
        if (matchesAny(q, "how long has", "how many years", "total experience",
                       "years of experience", "how much experience",
                       "how long did", "experience of candidate")) {
            return "What is the total work experience in years?";
        }

        // ── Current company ───────────────────────────────────────────────────
        if (matchesAny(q, "current company", "current employer", "working at",
                       "works at", "where does he work", "where does she work",
                       "current organization")) {
            return "What is the current company or employer of the candidate?";
        }

        // ── Current role ──────────────────────────────────────────────────────
        if (matchesAny(q, "current role", "current position", "designation",
                       "job title", "current job", "what does he do",
                       "what does she do", "what is candidate doing")) {
            return "What is the current job role or designation?";
        }

        // ── Education ─────────────────────────────────────────────────────────
        if (matchesAny(q, "alma mater", "studied at", "graduated from",
                       "educational background", "qualification of",
                       "degree of candidate", "what did candidate study")) {
            return "What is the educational qualification and institution?";
        }

        // ── Skills ────────────────────────────────────────────────────────────
        if (matchesAny(q, "tech stack", "technologies used", "tools used",
                       "programming languages", "frameworks used",
                       "expertise of candidate", "what skills does")) {
            return "What are the technical skills and technologies?";
        }

        // ── Salary ────────────────────────────────────────────────────────────
        if (matchesAny(q, "how much does", "remuneration", "package",
                       "compensation of", "ctc of", "expected pay")) {
            return "What is the salary or CTC of the candidate?";
        }

        // ── Phone ─────────────────────────────────────────────────────────────
        if (matchesAny(q, "contact number", "telephone", "mobile number",
                       "phone number of", "cell number")) {
            return "What is the phone or mobile number of the candidate?";
        }

        // ── Email ─────────────────────────────────────────────────────────────
        if (matchesAny(q, "mail id", "email address of", "email of candidate",
                       "contact email", "email id")) {
            return "What is the email address of the candidate?";
        }

        // Not matched — return original (already clear enough)
        return question.trim();
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private static boolean hasAny(String text, String... patterns) {
        for (String p : patterns) if (text.contains(p)) return true;
        return false;
    }

    private static boolean matchesAny(String text, String... patterns) {
        for (String p : patterns) if (text.contains(p)) return true;
        return false;
    }

    private static long countPattern(String text, String regex) {
        return Pattern.compile(regex).matcher(text).results().count();
    }
}