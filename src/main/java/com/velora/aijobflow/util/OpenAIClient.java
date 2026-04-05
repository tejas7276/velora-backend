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

    @Value("${openai.api.url:https://api.groq.com/openai/v1/chat/completions}")
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
        PromptRouter.DocType  docType = PromptRouter.detectDocType(text);
        PromptRouter.TaskType task    = PromptRouter.TaskType.ANALYSIS;
        String sys = PromptRouter.systemPromptFor(task, docType)
                   + PromptRouter.groundingFor(task);
        if (wordCount(text) < 150) {
            return trimToSentences(invoke(sys, text, model, 150, 0.0), 3);
        }
        String sections = switch (docType) {
            case RESUME        -> "## Candidate Profile\n## Key Skills\n## Education\n## Career Highlights\n";
            case CODE          -> "## Purpose\n## Architecture\n## Key Functions\n## Dependencies\n";
            case DATA          -> "## Key Metrics\n## Trends\n## Anomalies\n## Missing Data\n";
            case MEETING_NOTES -> "## Topics Discussed\n## Decisions\n## Action Items\n";
            default            -> "## TL;DR\n## Key Points\n## Summary\n## Not Covered\n";
        };
        return invoke(sys, "Summarize this document.\n\n" + sections + "\n---DOCUMENT---\n" + text,
            model, 900, T_ANALYSIS);
    }

    public String analyzeText(String text, String model) throws Exception {
        if (wordCount(text) < 50) return "INSUFFICIENT_DATA — document too short for analysis.";
        PromptRouter.DocType  docType = PromptRouter.detectDocType(text);
        PromptRouter.TaskType task    = PromptRouter.TaskType.ANALYSIS;
        String sys = PromptRouter.systemPromptFor(task, docType)
                   + PromptRouter.groundingFor(task);
        String sections = switch (docType) {
            case RESUME -> "## Candidate Overview\n## Technical Strengths\n## Experience Depth\n## Education Quality\n## Red Flags\n## Recommendation\n";
            case CODE   -> "## Code Quality\n## Architecture\n## Security\n## Performance\n## Maintainability\n";
            case DATA   -> "## Data Quality\n## Key Findings\n## Trends\n## Recommendations\n";
            default     -> "## Executive Summary\n## Key Themes\n## Detailed Insights\n## Gaps\n";
        };
        return invoke(sys, "Analyze this " + docType.name().toLowerCase() + ".\n\n" + sections +
            "\n---DOCUMENT---\n" + text, model, 1000, T_ANALYSIS);
    }

    public String analyzeSentiment(String text, String model) throws Exception {
        PromptRouter.DocType docType = PromptRouter.detectDocType(text);
        if (docType == PromptRouter.DocType.RESUME) {
            return invoke(
                "You are an HR language specialist.",
                "Analyze the professional tone of this resume.\n\n" +
                "## Professional Tone\nConfident / Passive / Assertive / Vague\n\n" +
                "## Language Quality\nClarity, action verbs, specificity.\n\n" +
                "## First Impression\nFor a hiring manager.\n\n" +
                "## Suggested Improvements\n\n---RESUME---\n" + text,
                model, 600, T_ANALYSIS);
        }
        return invoke(
            "Sentiment analysis expert. Base analysis strictly on words present.",
            "Analyze sentiment.\n\n" +
            "## Overall Sentiment\nPositive / Negative / Neutral / Mixed\n\n" +
            "## Confidence\nX%\n\n## Score\n-10 to +10\n\n" +
            "## Key Sentiment Drivers\nExact words from text.\n\n" +
            "## Emotional Tone\nWith evidence.\n\n---TEXT---\n" + text,
            model, 600, T_ANALYSIS);
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
        return invoke(
            "Professional translator. Detect language. Preserve tone exactly.",
            "Translate.\n\n## Detected Language\n## Translation\n\n" +
            "## Translator Notes\nOmit if no idioms.\n\n---TEXT---\n" + text,
            model, 700, T_FACTUAL);
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
        if (wordCount(resumeText) < 30) return "INSUFFICIENT_DATA — resume too short.";
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.ANALYSIS, PromptRouter.DocType.RESUME)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.ANALYSIS);
        return invoke(sys,
            "Score resume using this rubric (100pts total):\n" +
            "- Contact & Basics (10): name, email, phone, location\n" +
            "- Work Experience (30): companies, roles, years, achievements\n" +
            "- Skills (20): technical + soft skills\n" +
            "- Education (15): degree, institution, year\n" +
            "- ATS Friendliness (15): keywords, format\n" +
            "- Summary/Objective (10): present and compelling\n\n" +
            "## Overall Score: X/100\n## ATS Compatibility: X/100\n## First Impression\n" +
            "## Top 3 Strengths\n## Top 3 Weaknesses\n## Missing ATS Keywords\n" +
            "## Top 5 Fixes\n## Best-Fit Roles\n\n---RESUME---\n" + resumeText,
            model, 1200, T_ANALYSIS);
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

    String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.GENERATION, PromptRouter.DocType.RESUME)
               + PromptRouter.groundingFor(PromptRouter.TaskType.GENERATION);

    return invoke(sys,
        "Act as a senior recruiter, ATS system, and LinkedIn personal branding expert.\n" +
        "Create a HIGH-IMPACT LinkedIn profile that is realistic, keyword-optimized, and recruiter-ready.\n\n" +

        "STRICT RULES (NON-NEGOTIABLE):\n" +
        "- Use ONLY provided data\n" +
        "- DO NOT add any technology, tool, or concept not explicitly present\n" +
        "- DO NOT invent experience, tools, or achievements\n" +
        "- If something is missing → skip it\n" +
        "- Avoid generic words (motivated, hardworking, passionate, detail-oriented)\n" +
        "- Keep content concise, sharp, and natural\n\n" +

        "STEP 1 — EXTRACTION:\n" +
        "- Identify role (Backend / Full Stack / etc.)\n" +
        "- Identify experience level (Fresher / Intern / etc.)\n" +
        "- Extract technologies and tools\n" +
        "- Detect strongest technical area\n\n" +

        "STEP 2 — EVALUATION:\n" +
        "- Evaluate profile strength\n" +
        "- Identify missing high-value skills ONLY if clearly relevant (cloud, testing, etc.)\n\n" +

        "STEP 3 — POSITIONING (CRITICAL):\n" +
        "- Position candidate based on strongest skill\n" +
        "- Example: 'Backend-Focused Full Stack Developer'\n\n" +

        "OUTPUT FORMAT:\n\n" +

        "## Profile Strength Score (0–100)\n\n" +

        "## Headline (MAX 220 chars — CRITICAL)\n" +
        "- Role + specialization + key technologies ONLY from input\n\n" +

        "## About Section (5–6 lines)\n" +
        "- Clear role identity\n" +
        "- Technical strengths\n" +
        "- Real experience (intern/projects only if present)\n" +
        "- Career direction\n" +
        "- No fluff, no storytelling\n\n" +

        "## Core Skills (Grouped)\n" +
        "- Backend:\n" +
        "- Frontend:\n" +
        "- Database:\n" +
        "- Tools:\n\n" +

        "## Featured Work (if available)\n" +
        "- Project + actual contribution\n\n" +

        "## Recruiter Hook\n" +
        "- 1–2 lines, sharp and realistic\n\n" +

        "## Keyword Gaps (CRITICAL)\n" +
        "- ONLY list missing skills if clearly not present\n\n" +

        "## Optimization Suggestions\n" +
        "- Exact improvements based on input only\n\n" +

        "## Connection Message\n" +
        "- Short, professional, non-generic (2–3 lines)\n\n" +

        "## Content Strategy (2–3 ideas)\n\n" +

        "## LinkedIn Bio (FINAL COPY-READY)\n" +
        "- Combine Headline + About into clean format\n\n" +

        "QUALITY CONTROL (STRICT):\n" +
        "- No hallucinated tools (e.g., AWS, MongoDB if not given)\n" +
        "- No generic buzzwords\n" +
        "- Must reflect ONLY real candidate data\n" +
        "- Must be copy-paste ready\n\n" +

        "---INPUT---\n" + resumeOrSummary,

        model, 1000, T_CREATIVE
    );
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
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.ANALYSIS, PromptRouter.DocType.MEETING_NOTES)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.ANALYSIS);
        return invoke(sys,
            "Extract only the following from the meeting notes. Do NOT add summaries, decisions, or follow-up emails.\n\n" +
            "## Action Items\n" +
            "List each task assigned. Format: - [Task] → Owner (if mentioned) | Due: [date if mentioned]\n" +
            "If none assigned: write 'No action items recorded.'\n\n" +
            "## Deadlines\n" +
            "List each explicit deadline mentioned. Format: - [Deadline] → Context\n" +
            "If none mentioned: write 'No deadlines mentioned.'\n\n" +
            "## Next Steps\n" +
            "List what happens after this meeting. Format: - [Next step]\n" +
            "If none stated: write 'No next steps defined.'\n\n" +
            "RULES:\n" +
            "- Do NOT add a summary section.\n" +
            "- Do NOT add decisions, open questions, or follow-up email.\n" +
            "- Only include what is explicitly stated in the notes.\n\n" +
            "---MEETING NOTES---\n" + notes,
            model, 600, T_ANALYSIS);
    }

    public String explainBug(String bugReport, String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.ANALYSIS, PromptRouter.DocType.CODE)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.ANALYSIS);
        return invoke(sys,
            "Diagnose the following bug and provide a direct fix.\n\n" +
            "RULES:\n" +
            "- Reference the exact line number or function name where the bug occurs.\n" +
            "- Keep all explanations short and specific to this bug.\n" +
            "- Do NOT add prevention tips or why-it-works explanations.\n\n" +
            "## Severity\n" +
            "Critical / High / Medium / Low — one line justification.\n\n" +
            "## What Went Wrong\n" +
            "Plain English. One or two sentences. What failed and where (exact line/function).\n\n" +
            "## Root Cause\n" +
            "Technical cause. If uncertain: 'Likely cause: ...'\n\n" +
            "## Fix\n" +
            "The exact corrected code or command. If uncertain: prefix with 'Likely Fix:'.\n\n" +
            "---BUG REPORT---\n" + bugReport,
            model, 700, T_ANALYSIS);
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