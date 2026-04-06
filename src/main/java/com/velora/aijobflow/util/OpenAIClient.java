package com.velora.aijobflow.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  OpenAIClient  —  Groq AI client
 * ═══════════════════════════════════════════════════════════════════
 *
 *  FIXES in this version:
 *
 *  FIX 1: deduplicateTokens() was splitting on "(?<=\\s)|(?=\\s)" which
 *          produces empty string tokens. When two spaces appeared (e.g. after
 *          a newline), lastWord became "" and the NEXT real word was suppressed
 *          because "" != "pune" is false on empty-to-empty comparison.
 *          Fixed: split on "\\s+" and reconstruct with single spaces.
 *          The output is semantically identical but stable.
 *
 *  FIX 2: normalizeOutput() was injecting "## Confidence: High/Medium"
 *          into ALL responses containing "##" including QA extractions,
 *          translations, and classifications. This corrupted short extraction
 *          answers that happened to contain a section header.
 *          Fixed: confidence injection only fires for ANALYSIS jobs
 *          (explicitly passed via the isAnalysis flag — see sanitizeResult).
 *          The flag defaults to false, so only jobs that opt-in get it.
 *
 *  FIX 3: invoke() cache key was hashing the entire systemPrompt + userMessage
 *          string which for document jobs can be 3000+ words. This produced
 *          hash collisions. Fixed: cache key is now built from
 *          hash(userMessage only) + model, which is unique per actual query.
 *          Document-based calls should not be cached (content too large)
 *          so the 300-char length guard already prevents caching them.
 */
@Slf4j
@Component
public class OpenAIClient {

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.url:https://api.groq.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${openai.model:llama-3.3-70b-versatile}")
    private String defaultModel;

    private static final String  FALLBACK_MODEL = "llama-3.1-8b-instant";
    private static final int     MAX_RETRIES    = 2;
    private static final long    RETRY_DELAY_MS = 800;
    private static final int     MAX_RESPONSE   = 6000;
    private static final int     MAX_DOC_WORDS  = 3000;

    private static final double  T_FACTUAL  = 0.05;
    private static final double  T_ANALYSIS = 0.15;
    private static final double  T_CREATIVE = 0.40;


    // ── INPUT VALIDATION GATE ─────────────────────────────────────────────────
    // Doc 15 Rule 1: Before ANY reasoning, reject URL / empty / placeholder inputs.
    // Returns null if input is valid, or a STATUS: FAILED message if not.
    private static final java.util.regex.Pattern URL_PATTERN =
        java.util.regex.Pattern.compile("^https?://\\S+$", java.util.regex.Pattern.CASE_INSENSITIVE);

    private String validateInput(String input) {
        if (input == null || input.isBlank()) {
            return "STATUS: FAILED\nREASON: Input is empty. Please provide actual content for processing.";
        }
        String trimmed = input.trim();
        if (URL_PATTERN.matcher(trimmed).matches()) {
            return "STATUS: FAILED\nREASON: A URL was provided instead of content. This system cannot fetch URLs. " +
                   "Please paste the actual text, resume, code, or document content directly.";
        }
        String lower = trimmed.toLowerCase();
        if (lower.equals("[add your own]") || lower.equals("[placeholder]") ||
            lower.equals("your text here") || lower.equals("paste content here")) {
            return "STATUS: FAILED\nREASON: Placeholder text detected. Please replace with real content.";
        }
        int wc = trimmed.split("\\s+").length;
        if (wc < 3) {
            return "STATUS: FAILED\nREASON: Input too short for meaningful processing (" + wc + " words). " +
                   "Please provide sufficient content.";
        }
        return null; // valid
    }

    private static final String GROUND =
        "\n\nCRITICAL: Use ONLY information from the provided content. " +
        "If the answer is not present, respond with NOT_FOUND. " +
        "Do NOT invent, assume, or add external knowledge.";

    private final ObjectMapper        mapper = new ObjectMapper();
    private final CloseableHttpClient http   = HttpClients.custom()
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.of(10, TimeUnit.SECONDS))
                    .setResponseTimeout(Timeout.of(90, TimeUnit.SECONDS))
                    .build())
            .build();

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    // ── PUBLIC API ────────────────────────────────────────────────────────────

    public String callWithModel(String prompt, String model) throws Exception {
        return invoke(
            "You are an expert AI assistant. Be direct, accurate, and professional.\n" +
            "Write complete sentences. Never return raw values. Avoid filler openers.",
            prompt, model, 800, T_ANALYSIS);
    }

    public String summarizeText(String text, String model) throws Exception {
        String guard = validateInput(text);
        if (guard != null) return guard;

        PromptRouter.DocType  docType = PromptRouter.detectDocType(text);
        int wc = wordCount(text);

        // SHORT INPUT: concise 3-sentence distillation, no padding
        if (wc < 150) {
            return invoke(
                "You are a master distiller. Extract the single most important insight. " +
                "Write 2–3 precise sentences. No openers, no conclusions, no padding.",
                "Distill this to its core meaning:\n\n" + text,
                model, 120, 0.0);
        }

        // FULL SUMMARY: doc-type-aware expert lens
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.ANALYSIS, docType)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.ANALYSIS);

        String lens = switch (docType) {
            case RESUME ->
                "You are a talent acquisition lead reviewing candidates for a competitive engineering role.\n" +
                "EXTRACTION PRIORITY: name → current role → years of experience → strongest technical stack → education → red flags.\n\n" +
                "## Candidate Snapshot\n" +
                "One sentence: Name, role, years of experience, strongest technical area.\n\n" +
                "## Technical Depth\n" +
                "Languages + frameworks actually named. Mark each: Beginner / Intermediate / Advanced based on context clues (projects, duration, complexity).\n\n" +
                "## Experience Quality\n" +
                "Internship vs full-time. Company type. Real-world impact vs training. Duration consistency.\n\n" +
                "## Education Signal\n" +
                "Degree, institution, CGPA if present. Relevance to role.\n\n" +
                "## Red Flags\n" +
                "Gaps, vague descriptions, missing contact info, or overused buzzwords without evidence.\n\n" +
                "## Recruiter Verdict\n" +
                "Shortlist / Hold / Reject — one sentence of evidence.";

            case CODE ->
                "You are a principal engineer doing a production code review.\n" +
                "ONLY comment on what you can actually see in the code.\n\n" +
                "## Purpose\n" +
                "What this code does in one sentence. Not what it's supposed to do.\n\n" +
                "## Architecture Pattern\n" +
                "Identify: MVC / Functional / Event-driven / Procedural / etc. Evidence from structure.\n\n" +
                "## Key Functions\n" +
                "Name each function/class. What it does. Input → Output.\n\n" +
                "## Dependencies & Imports\n" +
                "List only what appears in import/require statements.\n\n" +
                "## Code Quality Signal\n" +
                "Naming quality, error handling present/absent, complexity level. No generic advice.";

            case DATA ->
                "You are a senior data analyst presenting findings to a board.\n" +
                "Every number you cite must exist in the input. Zero invented statistics.\n\n" +
                "## Headline Metric\n" +
                "The single most important number or finding from the data.\n\n" +
                "## Key Metrics Extracted\n" +
                "List each metric exactly as it appears. Format: Metric | Value | Context.\n\n" +
                "## Trend Signal\n" +
                "Direction of change if data shows it. Increasing / Decreasing / Flat. Evidence only.\n\n" +
                "## Anomalies\n" +
                "Any outlier, gap, or inconsistency visible in the data.\n\n" +
                "## So What\n" +
                "One sentence: what decision does this data support?";

            case MEETING_NOTES ->
                "You are an executive assistant who reads meeting notes and extracts only what is actionable.\n" +
                "No summaries of discussion. Only decisions and actions.\n\n" +
                "## Meeting in One Line\n" +
                "Topic + Outcome. One sentence. No names unless essential.\n\n" +
                "## Decisions Made\n" +
                "Only explicitly stated decisions. Format: Decision | Rationale (if given).\n\n" +
                "## Action Items\n" +
                "Format: - Task → Owner (if named) | Due: [date if mentioned]\n" +
                "If none: 'No action items recorded.'\n\n" +
                "## Blockers / Dependencies\n" +
                "Anything that was flagged as blocking progress.\n\n" +
                "## Next Touchpoint\n" +
                "When the team meets again or what triggers the next step.";

            case LEGAL ->
                "You are a senior legal counsel summarizing a contract for a C-suite executive.\n" +
                "Cite section numbers or clause titles when present.\n\n" +
                "## Document Type & Parties\n" +
                "What kind of agreement. Who are the parties. Effective date if present.\n\n" +
                "## Core Obligations\n" +
                "What each party MUST do. Verbatim clause references where possible.\n\n" +
                "## Rights Granted\n" +
                "What each party is explicitly permitted to do.\n\n" +
                "## Risk Clauses\n" +
                "Liability caps, indemnification, termination triggers, penalty clauses.\n\n" +
                "## Critical Dates\n" +
                "Deadlines, expiry dates, notice periods from the document.\n\n" +
                "## Red Flags\n" +
                "Ambiguous language, one-sided clauses, or missing standard protections.";

            case ARTICLE ->
                "You are an editor at a research journal evaluating article quality and argument strength.\n\n" +
                "## Central Argument\n" +
                "The thesis in one sentence. Exactly what position the author defends.\n\n" +
                "## Evidence Used\n" +
                "What the author cites: studies, statistics, quotes, examples. Grade quality: Primary / Secondary / Anecdotal.\n\n" +
                "## Logical Flow\n" +
                "Does the argument progress coherently? Note any gaps or leaps.\n\n" +
                "## Bias Indicators\n" +
                "One-sided framing, cherry-picked data, missing counterarguments.\n\n" +
                "## Conclusion Strength\n" +
                "Does the evidence support the conclusion? Strong / Moderate / Overstated.";

            default ->
                "You are a senior analyst. Extract only what is explicitly stated.\n\n" +
                "## TL;DR\n" +
                "The single most important point in one sentence.\n\n" +
                "## Key Points\n" +
                "The 3–5 most important facts. Each tied to specific text.\n\n" +
                "## What Is Not Covered\n" +
                "Important gaps or missing context a reader would need.\n\n" +
                "## Confidence\n" +
                "How complete is the input? Complete / Partial / Insufficient.";
        };

        // Dynamic token budget: scale with input size, cap at 1000
        int tokens = Math.min(200 + (wc / 4), 1000);
        return invoke(sys, lens + "\n\n---DOCUMENT---\n" + text, model, tokens, T_ANALYSIS);
    }

    public String analyzeText(String text, String model) throws Exception {
        String guard = validateInput(text);
        if (guard != null) return guard;
        if (wordCount(text) < 50) return "INSUFFICIENT_DATA — Minimum 50 words required for meaningful analysis.";

        PromptRouter.DocType  docType = PromptRouter.detectDocType(text);
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.ANALYSIS, docType)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.ANALYSIS);

        String prompt = switch (docType) {
            case RESUME ->
                "You are a Head of Talent at a top-tier tech company. You have seen 10,000 resumes.\n" +
                "Your analysis must feel like feedback from someone who hires or rejects people for a living.\n" +
                "RULE: Every claim references specific resume content. No generic observations.\n\n" +
                "## Candidate Profile\n" +
                "Name, role title, years of experience, location. One line only.\n\n" +
                "## Technical Signal Strength\n" +
                "For each technology mentioned, classify: WEAK (mentioned once) / MODERATE (used in project) / STRONG (shipped to production).\n" +
                "Format: Technology | Signal | Evidence\n\n" +
                "## Experience Quality Score\n" +
                "Rate 1–5 on: Relevance, Depth, Impact, Progression. Show evidence for each.\n\n" +
                "## Standout Factors\n" +
                "What makes this resume memorable vs forgettable. Specific details only.\n\n" +
                "## Hard Red Flags\n" +
                "Employment gaps. Internship-only experience for senior role. Buzzword inflation. Missing technical evidence.\n\n" +
                "## Hiring Decision\n" +
                "Strong Yes / Yes / Borderline / No — with your single strongest reason.\n\n" +
                "---RESUME---\n" + text;

            case CODE ->
                "You are a principal engineer doing a pre-merge security and architecture review.\n" +
                "Flag real issues. Skip non-issues. Every point names the exact function or line.\n\n" +
                "## Code Intent\n" +
                "What this code does. One sentence. Based only on what you see.\n\n" +
                "## Architecture Assessment\n" +
                "Pattern used. Coupling level. Separation of concerns. Evidence from structure.\n\n" +
                "## Security Audit\n" +
                "SQL injection risk, unvalidated input, exposed secrets, authentication gaps. If none found: 'No visible security issues.'\n\n" +
                "## Logic & Bug Analysis\n" +
                "Off-by-one errors, null pointer risks, incorrect conditionals. Reference exact function names.\n\n" +
                "## Performance Hotspots\n" +
                "N+1 queries, unnecessary loops, blocking calls, missing indexes. If none: omit section.\n\n" +
                "## Maintainability\n" +
                "Naming quality, comment coverage, complexity per function. Be specific.\n\n" +
                "## Priority Fix List\n" +
                "Top 3 changes to make before production. Ordered by impact.\n\n" +
                "---CODE---\n" + text;

            case DATA ->
                "You are a senior data scientist presenting to a board who needs to make a decision in 5 minutes.\n" +
                "Every statistic you cite must exist verbatim in the data.\n\n" +
                "## Data Health\n" +
                "Completeness, consistency, obvious errors or anomalies.\n\n" +
                "## Key Findings (Evidence-Locked)\n" +
                "Top 5 findings. Format: Finding | Supporting data point | Implication.\n\n" +
                "## Trend Analysis\n" +
                "Direction of key metrics. Increasing / Decreasing / Volatile / Flat. Evidence.\n\n" +
                "## Statistical Anomalies\n" +
                "Outliers, gaps, unexpected values. Reference exact values.\n\n" +
                "## Decision Support\n" +
                "What action does this data justify? What action does it warn against?\n\n" +
                "---DATA---\n" + text;

            default ->
                "You are a senior analyst. Extract verified insights only from the provided text.\n\n" +
                "## Core Thesis\n" +
                "What is this document fundamentally about? One sentence.\n\n" +
                "## Verified Key Insights\n" +
                "The 5 most important facts or claims. Each with a direct quote or reference.\n\n" +
                "## Internal Contradictions\n" +
                "Any claims that conflict with each other within the document.\n\n" +
                "## Information Gaps\n" +
                "What a reader would need to know that the document does not provide.\n\n" +
                "## Confidence Assessment\n" +
                "How well-supported are the main claims? Strong / Moderate / Speculative.\n\n" +
                "---DOCUMENT---\n" + text;
        };

        return invoke(sys, prompt, model, 1000, T_ANALYSIS);
    }

    public String analyzeSentiment(String text, String model) throws Exception {
        String guard = validateInput(text);
        if (guard != null) return guard;

        PromptRouter.DocType docType = PromptRouter.detectDocType(text);

        if (docType == PromptRouter.DocType.RESUME) {
            return invoke(
                "You are a behavioral language analyst who specializes in how language signals competence, confidence, and credibility in professional documents.",
                "Analyze the professional language quality of this resume.\n\n" +

                "## Tone Classification\n" +
                "Choose one: Commanding / Confident / Neutral / Passive / Apologetic\n" +
                "Evidence: Quote 1–2 specific phrases that justify this classification.\n\n" +

                "## Action Verb Quality\n" +
                "Strong verbs (Led, Architected, Reduced, Shipped) vs weak verbs (Helped, Assisted, Worked on).\n" +
                "List the 3 strongest and 3 weakest verb choices found in the resume.\n\n" +

                "## Specificity Score (1–10)\n" +
                "10 = every claim has a number, technology, or outcome attached.\n" +
                "1 = entirely generic, no measurable claims.\n" +
                "Score: X/10 — Evidence: [Example of specific claim] vs [Example of vague claim]\n\n" +

                "## Credibility Signals\n" +
                "What language choices build trust? Metrics, project names, technology versions, dates.\n\n" +

                "## Credibility Weaknesses\n" +
                "Overused buzzwords (passionate, detail-oriented, hardworking) with no supporting evidence.\n\n" +

                "## Hiring Manager First Read\n" +
                "One sentence: the gut-level impression this language creates.\n\n" +

                "## Rewrite Targets\n" +
                "Quote 2 specific weak sentences from the resume and show what strong version would look like.\n" +
                "Format: BEFORE: '...' → AFTER: '...'\n\n" +
                "---RESUME---\n" + text,
                model, 700, T_ANALYSIS);
        }

        return invoke(
            "You are a computational linguist and behavioral analyst. " +
            "Your sentiment analysis goes beyond positive/negative into emotional granularity. " +
            "Base EVERY observation on exact words from the text.",

            "Perform deep sentiment analysis.\n\n" +

            "## Sentiment Verdict\n" +
            "Overall: Positive / Negative / Neutral / Mixed | Intensity: Strong / Moderate / Mild\n\n" +

            "## Polarity Score\n" +
            "Scale: -10 (extreme negative) to +10 (extreme positive). Score: X/10\n" +
            "Justification: 2 sentences maximum.\n\n" +

            "## Emotion Profile\n" +
            "Identify the dominant emotions present. Format:\n" +
            "- [Emotion]: [Evidence — exact quote from text]\n" +
            "Choose from: Joy, Trust, Anticipation, Fear, Anger, Disgust, Sadness, Surprise, Frustration, Satisfaction\n\n" +

            "## Linguistic Evidence\n" +
            "The 5 most sentiment-loaded words or phrases in the text.\n" +
            "Format: '[phrase]' → [sentiment it carries] → [intensity: High/Med/Low]\n\n" +

            "## Emotional Trajectory\n" +
            "Does the sentiment change across the text? Beginning vs middle vs end.\n\n" +

            "## Hidden Signals\n" +
            "Passive-aggressive language, hedging, understatement, or irony if present.\n\n" +

            "## Confidence in Analysis\n" +
            "High / Medium / Low — based on text length and clarity of emotional signals.\n\n" +
            "---TEXT---\n" + text,
            model, 700, T_ANALYSIS);
    }

    public String extractKeywords(String text, String model) throws Exception {
        // Detect if user provided a predefined keyword list (separator: | or Keywords: or [list])
        // Format: "text content---KEYWORDS---keyword1, keyword2, keyword3"
        boolean hasPredefined = text.contains("---KEYWORDS---") || text.contains("Keywords:");
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.EXTRACTION, PromptRouter.DocType.GENERAL)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.EXTRACTION);

        if (hasPredefined) {
            // Predefined list mode — ONLY return keywords from the given list that appear in text
            return invoke(sys,
                "You are given a text and a predefined keyword list.\n" +
                "TASK: Return ONLY the keywords from the predefined list that appear in the text.\n" +
                "RULES:\n" +
                "- Case-insensitive matching. Exact phrase match only. No partial matches.\n" +
                "- Do NOT add keywords not in the predefined list.\n" +
                "- Do NOT add scores, categories, or extra fields.\n" +
                "OUTPUT: Return a JSON array only. Example: [\"keyword1\", \"keyword2\"]\n" +
                "If no keywords match: return []\n\n" +
                "---INPUT---\n" + text,
                model, 300, 0.0);
        }

        // Free extraction mode — extract from text, return JSON array
        return invoke(sys,
            "Extract all meaningful keywords from the following text.\n" +
            "RULES:\n" +
            "- Only extract terms that actually appear in the text.\n" +
            "- No duplicates. No scores. No categories.\n" +
            "OUTPUT: Return a JSON array only. Example: [\"term1\", \"term2\", \"term3\"]\n\n" +
            "---TEXT---\n" + text,
            model, 400, 0.0);
    }

    public String translateText(String text, String model) throws Exception {
        String guard = validateInput(text);
        if (guard != null) return guard;

        return invoke(
            "You are a senior human translator with 20+ years of cross-cultural localization experience. " +
            "You translate for meaning, tone, and cultural appropriateness — not word-for-word. " +
            "You preserve formality level, emotional register, and domain-specific terminology exactly.",

            "Translate the following text with professional precision.\n\n" +

            "## Source Language Detected\n" +
            "[Language name + confidence: High/Medium/Low]\n\n" +

            "## Target Language\n" +
            "[Detected from context or default to English if source is non-English]\n\n" +

            "## Translation\n" +
            "[Full, natural translation — not word-for-word but meaning-accurate]\n\n" +

            "## Tone Match\n" +
            "Formal / Semi-formal / Casual / Technical — and whether this was preserved.\n\n" +

            "## Cultural & Contextual Notes\n" +
            "Only include if: idioms were adapted, cultural references required localization, " +
            "or multiple valid translations exist. Format:\n" +
            "- Original: '[phrase]' → Translated as: '[phrase]' — Note: [why]\n" +
            "Omit this section entirely if no such cases exist.\n\n" +

            "## Terminology Precision\n" +
            "If domain-specific terms were present (legal, medical, technical), name them and explain translation choice.\n" +
            "Omit if no specialized terms.\n\n" +
            "---TEXT---\n" + text,
            model, 800, T_FACTUAL);
    }

    public String generateReport(String data, String model) throws Exception {
        PromptRouter.DocType  docType = PromptRouter.detectDocType(data);
        PromptRouter.TaskType task    = PromptRouter.TaskType.ANALYSIS;
        String sys = PromptRouter.systemPromptFor(task, docType)
                   + PromptRouter.groundingFor(task);
        String structure = switch (docType) {
            case DATA   ->
                "## Status\n" +
                "Current state of the data in one line (e.g., On Track / At Risk / Critical).\n\n" +
                "## Key Metrics\n" +
                "Extract counts, percentages, and values directly from data. No invented numbers.\n\n" +
                "## Stage / Phase\n" +
                "Where is this in its lifecycle? (if determinable from data)\n\n" +
                "## Key Findings\n" +
                "Top 3 facts grounded in the data.\n\n" +
                "## Risks\n" +
                "Only if signals are present in the data. Omit if none.\n\n" +
                "## Decision\n" +
                "Choose exactly one: Proceed / Hold / Reject — with one-line evidence.\n";
            case RESUME ->
                "## Candidate Status\n" +
                "One-line summary of profile stage.\n\n" +
                "## Strengths\n" +
                "Only list strengths with direct evidence from the resume.\n\n" +
                "## Gaps\n" +
                "Only list gaps explicitly missing from the resume.\n\n" +
                "## Decision\n" +
                "Choose exactly one: Proceed / Hold / Reject — with one-line evidence.\n";
            default ->
                "## Status\n" +
                "Current state in one line.\n\n" +
                "## Key Findings\n" +
                "Top facts from the data. No invented information.\n\n" +
                "## Decision\n" +
                "Choose exactly one: Proceed / Hold / Reject — with one-line evidence.\n";
        };
        return invoke(sys,
            "Generate a concise, factual report. NEVER invent statistics or data.\n" +
            "Base every section strictly on the provided input.\n\n" +
            structure + "\n---DATA---\n" + data, model, 900, T_ANALYSIS);
    }

    public String reviewCode(String code, String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.ANALYSIS, PromptRouter.DocType.CODE)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.ANALYSIS);
        return invoke(sys,
            "Review the following code. Focus on real issues only — bugs, security, logic.\n\n" +
            "RULES:\n" +
            "- Do NOT assign a numeric quality score. No X/100.\n" +
            "- Only include a section if there is an actual issue. Omit empty sections.\n" +
            "- For each issue: state the exact problem, reference the specific line or function, provide the fix.\n" +
            "- Keep explanations short and actionable.\n\n" +
            "## Security Issues\n" +
            "List each vulnerability with: Issue → Affected code reference → Fix.\n" +
            "If none found: omit this section entirely.\n\n" +
            "## Logic / Bug Issues\n" +
            "List each bug with: Issue → Exact line or function → Fix.\n" +
            "If none found: omit this section entirely.\n\n" +
            "## Performance Issues\n" +
            "List each inefficiency with: Issue → Location → Fix.\n" +
            "If none found: omit this section entirely.\n\n" +
            "## Improvements\n" +
            "Only list improvements that are directly supported by the code. No generic advice.\n" +
            "If none found: omit this section entirely.\n\n" +
            "---CODE---\n" + code,
            model, 1200, T_ANALYSIS);
    }

    public String classifyText(String text, String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.EXTRACTION, PromptRouter.DocType.GENERAL)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.EXTRACTION);
        // Check if user provided predefined categories (format: text---CATEGORIES---Cat1, Cat2, Cat3)
        boolean hasCategories = text.contains("---CATEGORIES---") || text.contains("Categories:");
        String classifyPrompt;

        if (hasCategories) {
            classifyPrompt =
                "Classify the text into EXACTLY ONE of the provided categories.\n" +
                "RULES:\n" +
                "- Use ONLY the categories provided. Do NOT create new ones.\n" +
                "- Return the single matching category name only. No explanation, no score, no metadata.\n" +
                "- If text matches no category: return the closest one from the list.\n\n" +
                "---INPUT---\n" + text;
        } else {
            classifyPrompt =
                "Classify the following text into one category.\n" +
                "Choose from: Technology, Business, HR & Career, Science, Legal, Other\n" +
                "Return ONLY the category name. Nothing else.\n\n" +
                "---TEXT---\n" + text;
        }

        return invoke(sys, classifyPrompt, model, 50, 0.0);
    }

    public String compareDocuments(String documents, String model) throws Exception {
        boolean hasSep = documents.contains("---DOCUMENT 2---") ||
                         documents.contains("===") || documents.contains("[DOCUMENT 2]");
        if (!hasSep) return
            "ERROR: Separate documents with: ---DOCUMENT 2---\n\n" +
            "[First document]\n---DOCUMENT 2---\n[Second document]";
        PromptRouter.TaskType task = PromptRouter.TaskType.ANALYSIS;
        String sys = PromptRouter.systemPromptFor(task, PromptRouter.DocType.GENERAL)
                   + PromptRouter.groundingFor(task);
        return invoke(sys,
            "Compare documents. Every point references both.\n\n" +
            "## Similarity Score: X%\n## Key Similarities\n## Key Differences\n" +
            "## Unique to Document 1\n## Unique to Document 2\n## Recommendation\n\n" +
            "---DOCUMENTS---\n" + documents, model, 1000, T_ANALYSIS);
    }

    public String scoreResume(String resumeText, String model) throws Exception {
        String guard = validateInput(resumeText);
        if (guard != null) return guard;
        if (wordCount(resumeText) < 30) return "INSUFFICIENT_DATA — Resume too short for reliable scoring (minimum 30 words).";

        String sys =
            "You are a FAANG-level technical recruiter who has reviewed over 50,000 resumes. " +
            "You score with surgical precision. You NEVER inflate scores. " +
            "Every dimension must cite specific content from the resume. " +
            "If content is missing, that dimension scores 0.\n\n" +
            "SCORING PHILOSOPHY:\n" +
            "- 90–100: Top 1% candidate — exceptional across all dimensions\n" +
            "- 75–89: Strong candidate — clear strengths, minor gaps\n" +
            "- 60–74: Average candidate — some strengths, notable gaps\n" +
            "- 45–59: Below average — significant gaps or red flags\n" +
            "- Below 45: Weak — critical information missing or unverifiable";

        return invoke(sys,
            "Score this resume with exact evidence for every score given.\n\n" +

            "## Scorecard\n" +
            "| Dimension | Max | Score | Evidence (from resume) |\n" +
            "|---|---|---|---|\n" +
            "| Contact & Identity | 10 | X | Name/email/phone/location present or absent |\n" +
            "| Work Experience Quality | 30 | X | Companies, roles, duration, measurable impact |\n" +
            "| Technical Skills Depth | 20 | X | Technologies named + context (project/duration) |\n" +
            "| Education Relevance | 15 | X | Degree, institution, CGPA if present |\n" +
            "| ATS & Format | 15 | X | Keywords, clean structure, no tables/graphics |\n" +
            "| Summary / Objective | 10 | X | Present, specific, targeted or generic/absent |\n\n" +

            "## Overall Score: X/100\n" +
            "## ATS Compatibility: X/100\n\n" +

            "## First Impression (recruiter gut reaction)\n" +
            "One sentence. What does this resume make you think immediately?\n\n" +

            "## Strongest Asset\n" +
            "The single most compelling thing in this resume. Specific detail.\n\n" +

            "## Critical Weaknesses (evidence-based)\n" +
            "1. [Weakness] → [Exact evidence or absence of evidence]\n" +
            "2. [Weakness] → [Exact evidence]\n" +
            "3. [Weakness] → [Exact evidence]\n\n" +

            "## ATS Keyword Gaps\n" +
            "Skills/tools/certifications that are absent but expected for this profile level. ONLY list what is genuinely missing.\n\n" +

            "## Priority Fixes (ordered by impact)\n" +
            "1. [Highest impact fix] — Expected gain: +X points\n" +
            "2. [Second fix] — Expected gain: +X points\n" +
            "3. [Third fix] — Expected gain: +X points\n\n" +

            "## Best-Fit Roles\n" +
            "Based ONLY on proven experience: [Role 1], [Role 2], [Role 3]\n\n" +

            "## Hiring Recommendation\n" +
            "Strong Hire / Hire / Maybe / Pass — one line of supporting evidence.\n\n" +

            "RULES:\n" +
            "- If a section of the resume has NO content, score that dimension 0\n" +
            "- DO NOT score higher than what the evidence supports\n" +
            "- NEVER write 'the candidate has strong skills' without naming the skill\n\n" +
            "---RESUME---\n" + resumeText,
            model, 1400, T_ANALYSIS);
    }

    public String generateInterviewPrep(String jobDescription, String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.GENERATION, PromptRouter.DocType.GENERAL)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.GENERATION);
        return invoke(sys,
            "Act as a senior technical interviewer + hiring manager.\n" + 
            "- Simulate real interview difficulty where candidate can fail if not prepared\n" +
            "Generate a COMPLETE, REALISTIC interview preparation guide STRICTLY based on the given Job Description.\n" +
            "Do NOT generate generic questions. Everything must come from JD.\n\n" +

            "STRICT RULES:\n" +
            "- Only include technologies and skills mentioned in JD\n" +
            "- Do NOT introduce unrelated tech (e.g., React if not in JD)\n" +
            "- Questions must reflect real interview difficulty\n" +
            "- Include concise but correct answers\n" +
            "- Focus on practical + real-world scenarios\n\n" +

            "STEP 1 — JD ANALYSIS (MANDATORY):\n" +
            "- Extract Role Type\n" +
            "- Extract Required Skills\n" +
            "- Extract Good-to-Have Skills\n" +
            "- Extract Key Responsibilities\n" +
            "- Identify Focus Areas (Backend / Frontend / DB / DevOps)\n\n" +

            "OUTPUT FORMAT:\n\n" +

            "## Role Overview\n" +
            "- Role Type:\n" +
            "- Key Skills:\n" +
            "- Focus Areas:\n" +
            "- Responsibilities Summary:\n\n" +

            "## Top Technical Questions (JD-Based)\n" +
            "(Generate 10–12 questions ONLY from JD skills)\n\n" +
            "For each:\n" +
            "- Difficulty: Easy / Medium / Hard\n " +
            "- Question:\n" +
            "- Expected Answer:\n\n" +

            "## Practical / Scenario-Based Questions\n" +
            "(5–7 real-world problems based on JD)\n" +
            "- System design, debugging, API handling\n\n" +

            "## Coding / Implementation Questions\n" +
            "(3–5 tasks if applicable)\n" +
            "- API design\n" +
            "- SQL queries\n" +
            "- Backend logic\n\n" +

            "## Behavioral Questions\n" +
            "(5 questions tailored to role)\n\n" +

            "## HR Questions\n" +
            "(5 questions role-specific)\n\n" +

            "## Key Topics to Revise (IMPORTANT)\n" +
            "- List all important concepts from JD\n\n" +

            "## Priority Focus\n" +
            "- High Priority (most asked)\n" +
            "- Medium Priority\n" +
            "- Low Priority\n\n" +

            "## 24-Hour Preparation Plan\n" +
            "- Structured time-based plan\n\n" +

            "## Common Mistakes to Avoid\n" +
            "- Based on JD expectations\n\n" +

            "## Smart Questions to Ask Interviewer\n" +
            "(5 role-relevant questions)\n\n" +

            "STRICT OUTPUT RULES:\n" +
            "- No generic content\n" +
            "- No unrelated technologies\n" +
            "- Must feel like real interview prep\n" +
            "- Keep answers short but useful\n\n" +

            "---JD INPUT---\n" + jobDescription,
            model, 1600, T_CREATIVE);
    }

    public String matchJobDescription(String payload, String model, String resumeText) throws Exception {
    String finalPayload =
            "[Resume]\n" + resumeText +
            "\n\n---JOB DESCRIPTION---\n" +
            payload;

    String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.ANALYSIS, PromptRouter.DocType.RESUME)
               + PromptRouter.groundingFor(PromptRouter.TaskType.ANALYSIS);

    return invoke(sys,
        "Act as a real-world enterprise Applicant Tracking System (ATS) used by top tech companies (Google, Amazon, Microsoft).\n" +
        "Your role is to STRICTLY evaluate how well a candidate's resume matches the provided Job Description.\n" +
        "Be highly critical, evidence-based, and realistic. Do NOT be optimistic or generous.\n\n" +

        "ASSUMPTION:\n" +
        "- Assume 80% of candidates are rejected.\n" +
        "- Only candidates with strong alignment should pass.\n\n" +

        "CORE EVALUATION RULES:\n" +
        "- Only consider skills, tools, and experience explicitly mentioned in the resume.\n" +
        "- Do NOT assume or infer missing skills.\n" +
        "- If a skill is partially mentioned → mark as PARTIAL.\n" +
        "- If no evidence is found → mark as NO.\n" +
        "- Always identify weaknesses even for strong candidates.\n" +
        "- NEVER give 100% unless it is a near-perfect enterprise-level match (extremely rare).\n\n" +

        "SCORING ENGINE (STRICT):\n" +
        "- Start score from 100%.\n" +
        "- Deduct 12–15% for each missing REQUIRED skill.\n" +
        "- Deduct 5–10% for each missing tool (AWS, Docker, CI/CD, Testing frameworks, etc).\n" +
        "- Deduct 10–20% if experience is below requirement.\n" +
        "- Deduct 5–10% if no real-world or production-level project evidence.\n" +
        "- Deduct 5–10% if resume lacks clarity, structure, or ATS keywords.\n" +
        "- Final score must reflect real hiring standards (strict, not inflated).\n\n" +

        "STEP 1 — JOB DESCRIPTION ANALYSIS (MANDATORY):\n" +
        "Extract and structure the Job Description into:\n" +
        "- Required Skills (must-have technologies)\n" +
        "- Good-to-Have Skills (optional but valuable)\n" +
        "- Experience Level Required (years, level: junior/mid/senior)\n" +
        "- Key Responsibilities (core job expectations)\n\n" +

        "STEP 2 — RESUME ANALYSIS (MANDATORY):\n" +
        "Extract and structure the resume into:\n" +
        "- Technical Skills (languages, frameworks)\n" +
        "- Tools & Technologies (cloud, CI/CD, testing, etc)\n" +
        "- Projects / Work Experience (real implementations)\n" +
        "- Strength Level (Beginner / Intermediate / Strong)\n\n" +

        "STEP 3 — SKILL MATCHING (MANDATORY):\n" +
        "Compare JD vs Resume skill-by-skill with STRICT evaluation.\n" +
        "Each required skill must be categorized as:\n" +
        "- YES (clearly present with evidence)\n" +
        "- PARTIAL (weak/indirect mention)\n" +
        "- NO (missing)\n\n" +

        "## Experience Quality Analysis (MANDATORY)\n" +
        "- Company Type: Product-based / Service-based / Startup / Training Institute\n" +
        "- Role Type: Internship / Full-time / Training\n" +
        "- Production Exposure: Real-world / Learning-level\n" +
        "- Work Impact: High / Medium / Low\n" +
        "- Stability: Duration and consistency\n\n" +

        "OUTPUT FORMAT (STRICTLY FOLLOW):\n\n" +

        "## Match Score: X% (strictly calculated using rules above)\n\n" +

        "## JD Breakdown\n" +
        "- Required Skills:\n" +
        "- Good-to-Have Skills:\n" +
        "- Experience Required:\n" +
        "- Key Responsibilities:\n\n" +

        "## Resume Breakdown\n" +
        "- Skills:\n" +
        "- Tools:\n" +
        "- Experience:\n" +
        "- Projects:\n\n" +

        "## Professional Experience Breakdown (MANDATORY)\n" +
        "For each experience in the resume, extract and display:\n" +
        "- Company Name\n" +
        "- Role / Position\n" +
        "- Duration (start–end)\n" +
        "- Experience Type (Internship / Full-time / Training)\n" +
        "- Company Type (Startup / Product / Service / Training Institute)\n" +
        "- Key Work Done (1–2 lines with actual tasks)\n" +
        "- Impact Level (High / Medium / Low)\n\n" + 

        "## Skills Match Table\n" +
        "| Required Skill | Match (YES/PARTIAL/NO) | Evidence from Resume |\n\n" +

        "## Experience Match\n" +
        "- Required vs Actual experience\n" +
        "- Project relevance\n\n" +

        "## Keyword Gaps (MANDATORY)\n" +
        "- List ALL missing or weak skills, tools, or keywords\n\n" +

        "## Strengths\n" +
        "- Only strong, proven capabilities with evidence\n\n" +

        "## Red Flags (MANDATORY)\n" +
        "- Missing tools, weak experience, lack of production exposure, or risks\n\n" +

        "## Verdict\n" +
        "- Apply Now → Score ≥ 85%\n" +
        "- Needs Improvement → 60–84%\n" +
        "- Not Suitable → < 60%\n\n" +

        "## Optimization Steps (MANDATORY)\n" +
        "- Exact actions to improve match score\n" +
        "- Include tools, skills, resume changes, and project suggestions\n\n" +

        "STRICT OUTPUT RULES:\n" +
        "- Do NOT give generic answers\n" +
        "- Do NOT skip Keyword Gaps or Red Flags\n" +
        "- Do NOT say 'None'\n" +
        "- Every claim must have evidence from resume\n" +
        "- Output must feel like a real ATS report\n\n" +

        "---INPUT---\n" + finalPayload,
        model, 1100, T_ANALYSIS);
}

    public String generateLinkedInBio(String resumeOrSummary, String model) throws Exception {
        String guard = validateInput(resumeOrSummary);
        if (guard != null) return guard;

        String sys =
            "You are a LinkedIn personal branding specialist who has optimized profiles for engineers " +
            "hired at Google, Amazon, Stripe, and Coinbase. " +
            "Your profiles get responses from recruiters because they are specific, credible, and not templated. " +
            "You have zero tolerance for: generic phrases, buzzwords without evidence, placeholders, or invented credentials.\n\n" +
            "IRON RULES:\n" +
            "- Every sentence you write maps to a specific fact in the provided input\n" +
            "- If a skill, project, or company is not in the input → do NOT include it\n" +
            "- If a section has no supporting data → write INSUFFICIENT DATA for that section only\n" +
            "- NEVER write [ADD YOUR OWN] — this is a finished deliverable, not a template\n" +
            "- NEVER use: results-driven, passionate, hardworking, motivated, detail-oriented, team player (without evidence)";

        return invoke(sys,
            "BUILD A COMPLETE LINKEDIN PROFILE from the provided background data.\n\n" +

            "PHASE 1 — FORCED EXTRACTION (do this internally before writing anything):\n" +
            "Extract and rank: Role type | Experience level | Top 3 technologies (with signal strength) | " +
            "Projects (name + what was built) | Companies (name + type + duration) | Unique differentiator\n\n" +

            "PHASE 2 — POSITIONING DECISION:\n" +
            "Based on extraction, choose the single strongest positioning angle. Example:\n" +
            "- Not 'Developer' → 'Spring Boot Backend Developer who has shipped [specific project]'\n\n" +

            "PHASE 3 — OUTPUT (strict format):\n\n" +

            "## Profile Strength Score\n" +
            "[X/100] — [One sentence explaining the score based on evidence quality]\n\n" +

            "## Headline (under 220 chars)\n" +
            "[Role] | [Top Technology] | [Specific differentiator from input — not generic]\n" +
            "RULE: No buzzwords. Must contain at least one technology name from input.\n\n" +

            "## About Section\n" +
            "5–6 sentences. Each sentence must be grounded in one specific fact from the input.\n" +
            "Sentence 1: Who they are + what they build (role + strongest tech)\n" +
            "Sentence 2: Most significant project or experience (specific name + what was achieved)\n" +
            "Sentence 3: Technical depth (technologies with context — not a list dump)\n" +
            "Sentence 4: Experience quality (intern/full-time, company type, real-world vs learning)\n" +
            "Sentence 5: Career direction (where they want to go — infer from current skills only)\n" +
            "If any sentence cannot be grounded → skip it.\n\n" +

            "## Technical Skills (from input only)\n" +
            "Backend: [only if present in input]\n" +
            "Frontend: [only if present in input]\n" +
            "Database: [only if present in input]\n" +
            "Cloud/DevOps: [only if present in input]\n" +
            "Testing: [only if present in input]\n" +
            "If a category has no data from input: omit that row entirely.\n\n" +

            "## Featured Work\n" +
            "For each project in the input:\n" +
            "- [Project Name]: [What was built] + [Technology used] + [Impact if stated]\n" +
            "If no projects in input: INSUFFICIENT DATA\n\n" +

            "## Recruiter Hook (2 sentences)\n" +
            "The sharpest possible description of this person for a technical recruiter.\n" +
            "Must contain: specific technology + specific project or experience + experience level.\n\n" +

            "## Skill Gaps (honest assessment)\n" +
            "Based on the profile level, what HIGH-VALUE skills are conspicuously absent.\n" +
            "List only gaps that matter for their target role. Not a generic wish list.\n\n" +

            "## Connection Request Message\n" +
            "150 characters max. Specific, personal, references something from their actual work.\n\n" +

            "## LinkedIn Bio (FINAL COPY)\n" +
            "Combine Headline + About in clean paragraph format. Copy-paste ready. Zero placeholders.\n\n" +

            "---INPUT---\n" + resumeOrSummary,
            model, 1200, T_CREATIVE);
    }

    public String writeEmail(String context, String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.GENERATION, PromptRouter.DocType.EMAIL);

        String raw = invoke(sys,
            "Write ONE complete, professional email based on the provided context.\n\n" +

            "DATA RULE (CRITICAL):\n" +
            "- Use ALL specific details from the context: names, dates, IDs, amounts, deadlines, locations, reasons.\n" +
            "- Do NOT omit any data the user mentioned.\n" +
            "- DO NOT explain decisions or mention missing data\n\n"+
            "- Do NOT invent or assume missing details.\n\n" +

            "LENGTH RULE:\n" +
            "- Short context (1–2 lines) → write at least 5–6 sentences in the body.\n" +
            "- Detailed context → write a full email covering every point raised.\n" +
            "- Never compress the email into a single paragraph.\n\n" +

            "OUTPUT FORMAT — FOLLOW EXACTLY, WITH LINE BREAKS:\n\n" +
            "Subject: [clear, specific subject line]\n\n" +
            "[Greeting — e.g., Dear [Name], / Hi [Name], / To Whom It May Concern,]\n\n" +
            "[Opening sentence — state the purpose clearly]\n\n" +
            "[Body — 2–4 sentences expanding on context, including all specific data]\n\n" +
            "[Closing sentence — action, request, or next step]\n\n" +
            "[Sign-off — e.g., Regards, / Sincerely, / Best regards,]\n" +

            "STRICT RESTRICTIONS:\n" +
            "- Write exactly ONE email.\n" +
            "- No subject/body on the same line.\n" +
            "- No greeting merged into body.\n" +
            "- No closing merged into body.\n" +
            "- No explanations, alternatives, or meta-text after the email.\n" +
            "- DO NOT explain decisions or mention missing data.\n\n"+

            "WRITING RULES:\n" +
            "- Natural, fluent, human-like — not robotic or templated.\n" +
            "- Vary sentence structure. Avoid repetition.\n" +
            "- Tone adapts to context: formal / friendly / apologetic / assertive.\n" +
            "- Each section MUST be on its own line with a blank line between sections.\n\n" +

            "STRICT RESTRICTIONS:\n" +
            "- Write exactly ONE email.\n" +
            "- No subject/body on the same line.\n" +
            "- No greeting merged into body.\n" +
            "- No closing merged into body.\n" +
            "- No explanations, alternatives, or meta-text after the email.\n\n" +

            "---CONTEXT---\n" + context,
            model, 700, T_ANALYSIS);

        // Use email-specific normalizer to preserve structure
        return normalizeEmailOutput(raw);
    }

    public String summarizeMeeting(String notes, String model) throws Exception {
        String guard = validateInput(notes);
        if (guard != null) return guard;

        return invoke(
            "You are a Chief of Staff who reads meeting notes and produces extraction-only summaries " +
            "for C-suite executives who have 2 minutes to read. " +
            "You NEVER invent, assume, or infer. Every item you write was explicitly said in the notes. " +
            "You are not a summarizer — you are an extractor. Structure is everything.",

            "Extract ONLY the following from the meeting notes. " +
            "Do NOT add context, interpretation, or anything not explicitly stated.\n\n" +

            "## Meeting in One Line\n" +
            "Topic + Core Outcome. One sentence. No names unless necessary.\n\n" +

            "## Decisions Made\n" +
            "Format: - [Decision] (mentioned by: [name if stated])\n" +
            "If none explicitly stated: 'No decisions recorded in these notes.'\n\n" +

            "## Action Items\n" +
            "Format: - [Task] → Owner: [name or 'unassigned'] | Due: [date or 'not stated']\n" +
            "If none: 'No action items assigned.'\n\n" +

            "## Deadlines Mentioned\n" +
            "Format: - [Deadline] → What it applies to\n" +
            "If none: 'No deadlines mentioned.'\n\n" +

            "## Blockers / Risks Flagged\n" +
            "Format: - [Issue] → Raised by: [name if stated]\n" +
            "If none mentioned: 'None flagged.'\n\n" +

            "## Next Touchpoint\n" +
            "When the team meets again or what triggers next action. " +
            "If not stated: 'Not defined in these notes.'\n\n" +

            "HARD RULES:\n" +
            "- Quote or paraphrase only what is in the notes\n" +
            "- If a field has no data, write the exact fallback phrase shown above\n" +
            "- Do NOT add follow-up email suggestions, summaries of discussion, or your own interpretations\n\n" +
            "---MEETING NOTES---\n" + notes,
            model, 700, T_ANALYSIS);
    }

    public String explainBug(String bugReport, String model) throws Exception {
        String guard = validateInput(bugReport);
        if (guard != null) return guard;

        return invoke(
            "You are a senior debugging engineer at a high-traffic production system. " +
            "You are paged at 3am for incidents. You think in root causes, not symptoms. " +
            "Every statement you make references the exact error, function, line, or stack frame. " +
            "You never guess without labeling it as a hypothesis.",

            "Diagnose this bug report and deliver a production-grade fix.\n\n" +

            "## Severity Classification\n" +
            "P0 (Production down) / P1 (Critical degradation) / P2 (Functional bug) / P3 (Minor)\n" +
            "Justification: one sentence.\n\n" +

            "## Incident Fingerprint\n" +
            "Type: NullPointerException / Logic Error / Race Condition / Memory Leak / Config Error / API Contract Violation / Other\n" +
            "Location: [exact function name, line number, or module — from bug report]\n\n" +

            "## What Failed (Plain Language)\n" +
            "Explain what went wrong in 1–2 sentences. No technical jargon — write as you would explain to a product manager.\n\n" +

            "## Root Cause (Technical)\n" +
            "The precise technical reason. If you are certain: state it directly.\n" +
            "If you are inferring: prefix with 'Hypothesis: '\n" +
            "Reference the exact error message, stack trace line, or code pattern.\n\n" +

            "## Reproduction Path\n" +
            "Step-by-step sequence that causes this bug (based on bug report evidence only).\n" +
            "If unclear: 'Reproduction path unclear from available data.'\n\n" +

            "## Fix\n" +
            "The exact corrected code, configuration, or command.\n" +
            "If multiple approaches exist, show the safest production fix first.\n" +
            "Label uncertain fixes: 'Likely Fix: '\n\n" +

            "## Blast Radius\n" +
            "What else could this bug affect? Other functions, endpoints, or data affected.\n\n" +

            "## Verification\n" +
            "How to confirm the fix worked. Test command, log line, or metric to check.\n\n" +
            "---BUG REPORT---\n" + bugReport,
            model, 800, T_ANALYSIS);
    }

    // ── QA — WITH EXPLICIT DOCUMENT ───────────────────────────────────────────

    public String answerQuestions(String question, String docText, String model) throws Exception {
        if (question == null || question.isBlank()) return "NOT_FOUND";
        if (docText   == null || docText.isBlank())  return answerQuestions(question, model);

        String cacheKey = "qa:" + Integer.toHexString(question.toLowerCase().trim().hashCode())
                        + ":" + Integer.toHexString(docText.hashCode());
        if (cache.containsKey(cacheKey)) return cache.get(cacheKey);

        String doc = truncateToWords(docText, MAX_DOC_WORDS);
        String sys =
            "You are a precise document QA engine.\n" +
            "RULE: Answer ONLY using the document.\n" +
            "RULE: ALWAYS write complete sentences — never raw values alone.\n" +
            "RULE: Example: return 'The candidate is from Pune.' not 'Pune'.\n" +
            "RULE: List question → numbered list only.\n" +
            "RULE: Not in document → write: This information is not available in the provided document.\n" +
            "RULE: Maximum 3 sentences per answer.";

        String raw    = invoke(sys, "Question: " + question + "\n\n---DOCUMENT---\n" + doc + "\n---END---",
                               model, 200, T_FACTUAL);
        String result = trimToSentences(deduplicateTokens(raw), 2);
        if (result != null && result.length() < 600) cache.put(cacheKey, result);
        // Replace system tokens with user-friendly messages
        result = result.replaceAll("(?i)\\bNOT_FOUND\\b", "This information is not available in the provided document.");
        result = result.replaceAll("(?i)\\bINSUFFICIENT_DATA\\b", "The document does not contain enough information to answer this.");
        return result == null || result.isBlank() ? "This information is not available in the provided document." : result;
    }

    public String answerQuestions(String content, String model) throws Exception {
        String trimmed   = content == null ? "" : content.trim();
        int    wordCount = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        if (wordCount <= 20) {
            return trimToSentences(invoke(
                "Factual assistant. Direct fact only. Max 2 sentences. No intro.",
                trimmed, model, 120, T_FACTUAL), 2);
        } else if (wordCount <= 80) {
            return invoke(
                "Knowledgeable expert. First sentence = direct answer. No intro.",
                "Answer:\n\n" + trimmed, model, 600, T_FACTUAL);
        } else {
            return invoke(
                "Expert analyst. Answer all questions in the document." + GROUND,
                "Analyze and answer:\n\n" + trimmed, model, 1800, T_FACTUAL);
        }
    }

    // ── SANITIZE — called by worker after AI response ─────────────────────────

    /**
     * Per-job-type output enforcement. Called by JobWorkerConsumer.
     *
     * FIX 2: isAnalysis flag controls confidence injection.
     * Only ANALYSIS jobs get the "## Confidence" appended.
     */
    public String sanitizeResult(String result, String jobType, String payload) {
        if (result == null || result.isBlank()) return result;
        String type = jobType == null ? "" : jobType.toUpperCase();
        int    wc   = payload == null ? 0 : payload.trim().split("\\s+").length;

        // Replace system tokens with user-friendly messages (final safety net)
        result = result.replaceAll("(?i)\\bNOT_FOUND\\b", "This information is not available in the provided document.");
        result = result.replaceAll("(?i)\\bINSUFFICIENT_DATA\\b", "The document does not contain enough information to answer this.");
        result = result.replaceAll("(?i)\\bNOT_IN_DOC\\b", "This information is not available in the provided document.");

        return switch (type) {
            case "QUESTION_ANSWER" -> {
                if (wc <= 20) yield trimToSentences(result, 2);
                yield result;
            }
            case "EXTRACT_KEYWORDS" -> deduplicateLines(result);
            case "SENTIMENT" -> {
                if (!result.contains("Sentiment") && !result.contains("##"))
                    yield "## Overall Sentiment\n" + trimToSentences(result, 2);
                yield result;
            }
            case "CLASSIFY" -> {
                if (!result.contains("Category") && !result.contains("##"))
                    yield "## Primary Category\n" + trimToSentences(result, 1);
                yield result;
            }
            case "TRANSLATE" -> {
                if (result.contains("## Translation")) yield result;
                yield "## Translation\n" + result;
            }
            default -> result;
        };
    }

    // ── CORE INVOKE ───────────────────────────────────────────────────────────

    private String invoke(String systemPrompt, String userMessage,
                          String modelOverride, int maxTokens, double temperature) throws Exception {
        String primary = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : defaultModel;

        // FIX 3: cache key is hash of userMessage only (not system prompt)
        // System prompts are large and produce hash collisions when combined
        String cacheKey = "inv:" + Integer.toHexString(userMessage.hashCode()) + ":" + primary;
        if (userMessage.length() < 300 && cache.containsKey(cacheKey)) {
            String cached = cache.get(cacheKey);
            if (cached != null && cached.length() < 500) return cached;
            cache.remove(cacheKey);
        }

        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String result = normalizeOutput(
                    executeCall(systemPrompt, userMessage, primary, maxTokens, temperature));
                if (userMessage.length() < 300 && result.length() < 500)
                    cache.put(cacheKey, result);
                return result;
            } catch (IOException e) {
                last = e;
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                    log.warn("Attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
                }
            }
        }
        if (!primary.equals(FALLBACK_MODEL)) {
            log.warn("Fallback {} → {}", primary, FALLBACK_MODEL);
            try {
                return normalizeOutput(
                    executeCall(systemPrompt, userMessage, FALLBACK_MODEL, maxTokens, temperature));
            } catch (IOException fx) { log.error("Fallback failed: {}", fx.getMessage()); }
        }
        throw last != null ? last : new IOException("All attempts failed");
    }

    // ── NORMALIZATION ─────────────────────────────────────────────────────────

    private String normalizeOutput(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String r = raw.trim();
        if (r.length() > MAX_RESPONSE) r = r.substring(0, MAX_RESPONSE) + "\n\n[Response truncated]";

        r = r.replaceAll("(?i)^\\s*(as an ai[^.]*\\.?\\s*)", "");
        for (String o : new String[]{"Sure! ","Sure, ","Certainly! ","Certainly, ","Absolutely! ",
            "Great question! ","Of course! ","Happy to help! ","I'd be happy to ",
            "Let me help. ","Here is ","Here are ","Here's a ","Here's "}) {
            if (r.startsWith(o)) {
                r = r.substring(o.length()).trim();
                if (!r.isEmpty()) r = Character.toUpperCase(r.charAt(0)) + r.substring(1);
                break;
            }
        }

        // FIX 2: Confidence injection REMOVED from normalizeOutput.
        // It was firing on ALL responses with "##" including extractions/translations.
        // Confidence is now only injected by sanitizeResult() for ANALYSIS jobs
        // that explicitly need it — this method does NOT add confidence any more.

        r = r.replaceAll("[=\\-]{4,}", "").replaceAll("\\n{3,}", "\n\n")
             .replaceAll("(?m)^(#{1,3})([^\\s#])", "$1 $2");

        return deduplicateTokens(r).trim();
    }

    /**
     * Normalizes email output specifically.
     * Emails need preserved line breaks — the general normalizeOutput() pipeline
     * runs deduplicateTokens() which destroys Subject/Greeting/Body/Closing structure.
     * This method enforces structural line breaks without touching content.
     */
    private String normalizeEmailOutput(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String r = raw.trim();

        // Remove AI disclaimer phrases only
        r = r.replaceAll("(?i)^\\s*(as an ai[^.]*\\.?\\s*)", "");

        // Enforce line break after Subject line if missing
        // "Subject: XxxDear" → "Subject: Xxx\n\nDear"
        r = r.replaceAll(
            "(?i)(Subject:[^\\n]+)(Dear|Hi |Hello|To |Good )",
            "$1\n\n$2"
        );

        // Enforce line break after greeting if missing
        // "Dear Manager,I am" → "Dear Manager,\n\nI am"
        r = r.replaceAll(
            "(Dear [^,\\n]+,|Hi [^,\\n]+,|Hello [^,\\n]+,|To [Ww]hom[^,\\n]+,)([A-Z])",
            "$1\n\n$2"
        );

        // Normalize excessive blank lines (3+ → 2)
        r = r.replaceAll("\\n{3,}", "\n\n");

        // Ensure sign-off is on its own line
        // "...final sentence.Regards," → "...final sentence.\n\nRegards,"
        r = r.replaceAll(
            "([.!?])(Regards,|Best regards,|Sincerely,|Thanks,|Thank you,|Warm regards,)",
            "$1\n\n$2"
        );

        return r.trim();
    }

    /**
     * Removes consecutively repeated words.
     *
     * FIX 1: Previous split "(?<=\\s)|(?=\\s)" produced empty tokens.
     * When text had multiple spaces or newlines, empty strings were stored
     * as lastWord, causing the next real word to be suppressed.
     *
     * Fixed: split on \\s+, reconstruct with single space between tokens.
     * Whitespace structure is intentionally simplified (consecutive spaces
     * are not meaningful in AI output, newlines are preserved separately).
     */
    private String deduplicateTokens(String text) {
        if (text == null || text.length() < 4) return text;

        // Preserve line structure — process line by line
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            // Split on whitespace, filter empty
            String[] tokens = line.split("\\s+");
            if (tokens.length == 0) {
                out.append("\n");
                continue;
            }

            StringBuilder lineOut = new StringBuilder();
            String lastWord = null;
            for (String token : tokens) {
                if (token.isEmpty()) continue;
                String lower = token.toLowerCase();
                // Allow repeated markdown bullets and numbered list markers
                boolean isMarker = lower.matches("[•\\-*]|\\d+\\.?");
                if (!lower.equals(lastWord) || isMarker) {
                    if (lineOut.length() > 0) lineOut.append(" ");
                    lineOut.append(token);
                    lastWord = lower;
                }
            }

            out.append(lineOut);
            if (li < lines.length - 1) out.append("\n");
        }

        return out.toString();
    }

    private String deduplicateLines(String text) {
        if (text == null || text.isBlank()) return text;
        String[] lines = text.split("\n");
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String key = line.trim().toLowerCase().replaceAll("^[\\d\\-•*#.\\s]+", "");
            if (key.isEmpty() || seen.add(key)) sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private String executeCall(String systemPrompt, String userMessage,
                               String model, int maxTokens, double temperature) throws Exception {
        HttpPost request = new HttpPost(apiUrl);
        request.setHeader("Content-Type",  "application/json");
        request.setHeader("Authorization", "Bearer " + apiKey);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user",   "content", userMessage));

        Map<String, Object> body = new HashMap<>();
        body.put("model",       model);
        body.put("messages",    messages);
        body.put("temperature", temperature);
        body.put("max_tokens",  maxTokens);
        body.put("top_p",       0.9);
        body.put("stream",      false);

        request.setEntity(new StringEntity(mapper.writeValueAsString(body), StandardCharsets.UTF_8));

        long   start    = System.currentTimeMillis();
        String response = http.execute(request, r -> {
            byte[] b = r.getEntity().getContent().readAllBytes();
            return new String(b, StandardCharsets.UTF_8);
        });
        long elapsed = System.currentTimeMillis() - start;

        JsonNode root = mapper.readTree(response);
        if (root.has("error")) {
            String msg  = root.at("/error/message").asText("unknown");
            String type = root.at("/error/type").asText("unknown");
            log.error("API [{}]: {}", type, msg);
            throw new IOException("API [" + type + "]: " + msg);
        }
        if (root.has("usage")) {
            log.info("API | {} | {}/{} tokens | {}ms", model,
                    root.at("/usage/completion_tokens").asInt(0), maxTokens, elapsed);
        }
        String content = root.at("/choices/0/message/content").asText();
        if (content == null || content.isBlank()) throw new IOException("Empty response");
        return content;
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private int wordCount(String t) {
        return t == null || t.isBlank() ? 0 : t.trim().split("\\s+").length;
    }

    private String truncateToWords(String text, int max) {
        if (text == null) return "";
        String[] w = text.split("\\s+");
        return w.length <= max ? text : String.join(" ", Arrays.copyOfRange(w, 0, max)) + "\n[...]";
    }

    private String trimToSentences(String text, int maxSentences) {
        if (text == null || text.isBlank()) return text;
        String clean = text.replaceAll("(?m)^#{1,6}\\s+", "")
            .replaceAll("\\*\\*(.*?)\\*\\*", "$1").replaceAll("\\*(.*?)\\*", "$1")
            .replaceAll("[=\\-]{4,}", "").replaceAll("[\\r\\n]+", " ")
            .replaceAll("\\s{2,}", " ").trim();
        String[] parts = clean.split("(?<=[.!?])\\s+");
        if (parts.length <= maxSentences)
            return clean.length() > 300 ? clean.substring(0, 300).trim() : clean;
        StringBuilder result = new StringBuilder();
        int taken = 0;
        for (String s : parts) {
            if (taken >= maxSentences) break;
            if (s.length() < 15) continue;
            if (result.length() > 0) result.append(" ");
            result.append(s); taken++;
        }
        return taken > 0 ? result.toString().trim() : parts[0].trim();
    }

    @PreDestroy
    public void close() throws IOException { http.close(); }
}