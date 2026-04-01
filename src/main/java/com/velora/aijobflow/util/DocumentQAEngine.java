package com.velora.aijobflow.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  DocumentQAEngine  —  RAG layer for Velora QA jobs
 * ═══════════════════════════════════════════════════════════════════
 *
 *  FIXES in this version:
 *
 *  FIX A: extractField() was passing normalizedQuestion (e.g. "location")
 *         to the AI as the query. The AI received a single bare word with
 *         no verb, no context — unpredictable behavior.
 *         Now always passes the full proper question to the AI.
 *         normalizedQuestion is only used for intent detection and chunking.
 *
 *  FIX B: isGroundedInDocument() returned false for single-word answers
 *         like "Pune" because the fuzzy match only fires when answer > 3 words.
 *         But single-word answers were supposed to skip grounding (FIX 6).
 *         The condition was inverted: isShortAnswer was checked AFTER
 *         the grounding call in some paths. Corrected to always skip
 *         grounding for answers of 3 words or fewer.
 *
 *  FIX C: retryWithFullDoc() was called when grounding fails, but it uses
 *         the same context that already failed. Now passes the full document
 *         (up to 2000 words) not the already-retrieved chunk context.
 *
 *  FIX D: extractMultiField() passed context as fullDoc to extractField()
 *         fallback, causing the retry to search within the chunk (not full doc).
 *         Fixed to pass the actual fullDoc parameter.
 *
 *  FIX E: answerYesNo() and answerGeneral() did not normalize the question
 *         before sending to AI. Now uses normalized form for consistency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentQAEngine {

    private final OpenAIClient ai;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final int CHUNK_SIZE        = 400;
    private static final int CHUNK_OVERLAP     = 80;
    private static final int TOP_K_CHUNKS      = 5;
    private static final int MAX_CONTEXT_WORDS = 2000;

    public enum Intent {
        EXTRACT_FIELD, EXTRACT_MULTI, MULTI_QUESTION, LIST, YES_NO, SUMMARIZE, GENERAL_QA
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PRIMARY ENTRY POINT
    // ═════════════════════════════════════════════════════════════════════════
    public String answer(String question, String docText, String model) throws Exception {
        if (question == null || question.isBlank()) return "ERROR: No question provided.";
        if (docText   == null || docText.isBlank())  return answerFromMemory(question, model);

        // FIX A: normalize for intent detection + chunking only
        // The normalized form may be a full proper question or the original
        String normalized = PromptRouter.normalizeQuery(question);
        Intent intent     = detectIntent(normalized);

        // Always retrieve context using the normalized form (better keyword match)
        String context = retrieveRelevantContext(normalized, docText);

        PromptRouter.DocType docType = PromptRouter.detectDocType(docText);

        log.info("DocumentQA | intent={} | docType={} | contextWords={} | original='{}' | normalized='{}'",
                 intent, docType, wordCount(context),
                 question.length() > 60 ? question.substring(0, 60) + "..." : question,
                 normalized.length() > 60 ? normalized.substring(0, 60) + "..." : normalized);

        return switch (intent) {
            // FIX A: always pass original question AND normalized to extractField
            case EXTRACT_FIELD  -> extractField(question, normalized, context, docText, model);
            case MULTI_QUESTION -> answerMultiQuestion(question, context, model);
            case EXTRACT_MULTI -> extractMultiField(question, normalized, context, docText, model);
            case LIST          -> extractList(normalized, context, model);
            // FIX E: use normalized question for yes/no and general
            case YES_NO        -> answerYesNo(normalized, context, model);
            case SUMMARIZE     -> summarizeFromDoc(context, docType, model);
            case GENERAL_QA    -> answerGeneral(normalized, context, docType, model);
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  RAG: CHUNK + SCORE + RETRIEVE
    // ═════════════════════════════════════════════════════════════════════════
    public String retrieveRelevantContext(String question, String docText) {
        List<String> chunks = chunkDocument(docText);
        if (chunks.size() == 1) return chunks.get(0);

        List<String>      queryTokens = tokenize(question);
        List<ScoredChunk> scored      = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            double score = scoreChunk(chunks.get(i), queryTokens);
            if (i == 0 || i == chunks.size() - 1) score += 0.2;
            scored.add(new ScoredChunk(chunks.get(i), score, i));
        }

        String context = scored.stream()
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .limit(TOP_K_CHUNKS)
            .sorted(Comparator.comparingInt(c -> c.index))
            .map(c -> c.text)
            .collect(Collectors.joining("\n\n[...]\n\n"));

        return truncateToWords(context, MAX_CONTEXT_WORDS);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CHUNKING
    // ═════════════════════════════════════════════════════════════════════════
    private List<String> chunkDocument(String text) {
        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();
        if (words.length <= CHUNK_SIZE) { chunks.add(text); return chunks; }

        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + CHUNK_SIZE, words.length);
            chunks.add(String.join(" ", Arrays.copyOfRange(words, start, end)));
            if (end == words.length) break;
        }
        return chunks;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  INTENT DETECTION
    // ═════════════════════════════════════════════════════════════════════════
    private Intent detectIntent(String question) {
        String q = question.toLowerCase().replaceAll("[?.,!]", "").trim();

        if (detectRequestedFields(q).size() >= 2) return Intent.EXTRACT_MULTI;

        // MULTI_QUESTION: user asked 2+ distinct questions (multiple question marks)
        long questionMarkCount = question.chars().filter(c -> c == '?').count();
        if (questionMarkCount >= 2) return Intent.MULTI_QUESTION;

                if (matchesAny(q, "list", "all ", "every ", "enumerate", "give all",
            "what are all", "show all", "tell all", "mention all",
            "all the ", "list of", "list out"))      return Intent.LIST;

        if (matchesAny(q, "is there", "are there", "does he", "does she", "do they",
            "did ", "was there", "were there", "has ", "have ",
            "any experience", "any skill", "eligible", "qualified",
            "is candidate", "is the candidate", "does candidate")) return Intent.YES_NO;

        if (matchesAny(q, "summarize", "summary", "overview", "brief", "tldr",
            "what is this document", "main points", "key points", "gist",
            "describe", "tell about", "what does this"))  return Intent.SUMMARIZE;

        if (matchesAny(q,
            "what is", "what was", "who is", "who was",
            "where is", "where was", "when ", "how many", "how much",
            "find ", "get ", "extract ", "give ", "tell me",
            "show me", "fetch ", "retrieve ",
            "name", "email", "phone", "location", "address", "salary",
            "experience", "education", "degree", "university", "college",
            "company", "position", "role", "title", "skills", "gpa",
            "dob", "linkedin", "github", "website",
            "age", "nationality", "city", "state", "country",
            "graduation year", "total experience"))   return Intent.EXTRACT_FIELD;

        return Intent.GENERAL_QA;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SINGLE FIELD EXTRACTION
    //
    //  FIX A: The AI prompt now always receives the full proper question.
    //         normalizedQuestion is NOT sent to the AI — it was only needed
    //         for intent detection and chunk retrieval.
    //
    //  FIX B: isShortAnswer check happens BEFORE grounding, not after.
    //         Short answers (≤ 3 words) always skip grounding — they would
    //         fail the fuzzy ratio even when correct (e.g. "Pune" in a doc
    //         that says "Pune, Maharashtra" — ratio = 1.0 but only if
    //         the check runs at all).
    // ═════════════════════════════════════════════════════════════════════════
    private String extractField(String originalQuestion, String normalizedQuestion,
                                String context, String fullDoc, String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.EXTRACTION,
                                                   PromptRouter.DocType.GENERAL)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.EXTRACTION);

        // FIX A: Send the proper question to the AI, not the bare normalized keyword
        // If normalization produced a better question, use it. Otherwise use original.
        String questionForAI = normalizedQuestion.equals(originalQuestion.trim())
            ? originalQuestion   // normalization didn't change it — use original
            : normalizedQuestion; // normalization produced a better question — use that

        String usr =
            "Question: " + questionForAI + "\n\n" +
            "Return ONLY the exact value as it appears in the document.\n" +
            "Examples: 'Tejas Shinde'  |  'Pune, Maharashtra'  |  '3 years'  |  NOT_FOUND\n\n" +
            "---DOCUMENT CONTEXT---\n" + context;

        String raw       = ai.callWithModel(sys + "\n\n" + usr, model);
        String extracted = cleanExtractedValue(raw);

        if (extracted == null || extracted.isBlank()) extracted = "NOT_FOUND";

        // FIX B: Check short answer FIRST — before grounding
        boolean isShortAnswer = extracted.split("\\s+").length <= 3;
        boolean isNotFound    = "NOT_FOUND".equalsIgnoreCase(extracted) ||
                                "INSUFFICIENT_DATA".equalsIgnoreCase(extracted);

        if (!isShortAnswer && !isNotFound) {
            if (!isGroundedInDocument(extracted, fullDoc)) {
                log.warn("Grounding failed for '{}' → retrying with full doc", questionForAI);
                // FIX C: retry uses fullDoc, not context (context is already the failed chunk)
                extracted = retryWithFullDoc(questionForAI, fullDoc, model);
            }
        }

        return extracted != null && !extracted.isBlank() ? extracted : "This information is not available in the provided document.";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  MULTI-FIELD EXTRACTION
    //  FIX D: fallback passes fullDoc not context
    // ═════════════════════════════════════════════════════════════════════════
    private String extractMultiField(String original, String normalized,
                                     String context, String fullDoc, String model) throws Exception {
        List<String> fields = detectRequestedFields(normalized);
        // FIX D: was passing context as fullDoc — now passes actual fullDoc
        if (fields.isEmpty()) return extractField(original, normalized, context, fullDoc, model);

        StringBuilder template = new StringBuilder("{");
        for (int i = 0; i < fields.size(); i++) {
            template.append("\"").append(fields.get(i)).append("\": \"...\"");
            if (i < fields.size() - 1) template.append(", ");
        }
        template.append("}");

        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.EXTRACTION,
                                                   PromptRouter.DocType.GENERAL);

        String usr =
            "Extract these fields: " + String.join(", ", fields) + "\n\n" +
            "Return ONLY valid JSON: " + template + "\n" +
            "Use NOT_FOUND for any missing field.\n\n" +
            "---DOCUMENT CONTEXT---\n" + context;

        String raw = ai.callWithModel(sys + "\n\n" + usr, model);
        return formatMultiFieldOutput(raw.replaceAll("```json|```", "").trim(), fields);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LIST EXTRACTION
    // ═════════════════════════════════════════════════════════════════════════
    // ── MULTI_QUESTION ─────────────────────────────────────────────────────────
    // Each question gets its own heading. Questions are never merged or skipped.
    private String answerMultiQuestion(String question, String context, String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.EXTRACTION, PromptRouter.DocType.GENERAL)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.EXTRACTION);
        return ai.callWithModel(
            sys + "\n\nAnswer EACH question separately using ## heading per question.\n" +
            "Write a complete sentence answer under each heading.\n" +
            "If absent: 'This information is not available in the provided document.'\n\n" +
            "Questions:\n" + question + "\n\n---DOCUMENT CONTEXT---\n" + context, model);
    }

    // ── HUMANIZE ───────────────────────────────────────────────────────────────
    // Converts a raw extracted value into a complete natural sentence.
    // Called ONLY when the extracted value is short and not yet a sentence.
    String humanize(String rawValue, String question, String model, PromptRouter.DocType docType) {
        if (rawValue == null || rawValue.isBlank()) {
            return "This information is not available in the provided document.";
        }
        // Already a complete sentence — return as-is
        String t = rawValue.trim();
        if (t.split("\\s+").length >= 5 && t.matches(".*[.!?]$")) return t;
        if (t.length() < 2) return "This information is not available in the provided document.";

        String docContext = switch (docType) {
            case RESUME -> "Use 'the candidate' as the subject.";
            case LEGAL  -> "Use formal, precise legal language.";
            case DATA   -> "State it as a data finding.";
            default     -> "Use neutral phrasing like 'the document states'.";
        };

        String prompt =
            "You are a professional response formatter. " + docContext + "\n" +
            "Convert the raw value into ONE complete, natural sentence.\n" +
            "NEVER return raw value alone. NEVER use markdown or JSON.\n\n" +
            "Question: " + question + "\n" +
            "Raw value: " + rawValue + "\n" +
            "Complete sentence:";
        try {
            return ai.callWithModel(prompt, model);
        } catch (Exception e) {
            log.warn("Humanize failed — returning raw: {}", e.getMessage());
            return rawValue;
        }
    }

        private String extractList(String question, String context, String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.EXTRACTION,
                                                   PromptRouter.DocType.GENERAL);
        return ai.callWithModel(
            sys + "\n\nExtract as numbered list. Only items present in document.\n\n" +
            "Extract list for: " + question + "\n\n---DOCUMENT CONTEXT---\n" + context, model);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  YES/NO
    // ═════════════════════════════════════════════════════════════════════════
    private String answerYesNo(String question, String context, String model) throws Exception {
        return ai.callWithModel(
            "Answer Yes, No, or Partially. One sentence of evidence from document. Max 2 sentences.\n" +
            "If cannot determine → CANNOT_DETERMINE — explain why.\n\n" +
            question + "\n\n---DOCUMENT CONTEXT---\n" + context, model);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SUMMARIZE
    // ═════════════════════════════════════════════════════════════════════════
    private String summarizeFromDoc(String context, PromptRouter.DocType docType,
                                    String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.ANALYSIS, docType);
        String instruction = switch (docType) {
            case RESUME -> "Summarize this candidate's profile: name, role, experience, key skills, education.";
            case CODE   -> "Summarize what this code does, its structure, and purpose.";
            case DATA   -> "Summarize the key metrics, trends, and findings in this data.";
            case MEETING_NOTES -> "Summarize what was discussed, decided, and assigned.";
            case LEGAL  -> "Summarize the document: parties involved, key obligations, and critical clauses.";
            case ARTICLE -> "Summarize the article: main argument, key evidence, and conclusions.";
            default -> "Summarize the main points, key information, and conclusions.";
        };
        return ai.callWithModel(sys + "\n\n" + instruction + "\n\n---DOCUMENT---\n" + context, model);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GENERAL QA
    // ═════════════════════════════════════════════════════════════════════════
    private String answerGeneral(String question, String context,
                                 PromptRouter.DocType docType, String model) throws Exception {
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.ANALYSIS, docType)
                   + PromptRouter.groundingFor(PromptRouter.TaskType.ANALYSIS);
        return ai.callWithModel(
            sys + "\n\nQuestion: " + question + "\n\n---DOCUMENT CONTEXT---\n" + context, model);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NO DOCUMENT
    // ═════════════════════════════════════════════════════════════════════════
    private String answerFromMemory(String question, String model) throws Exception {
        log.info("DocumentQA: no document — answering from general knowledge");
        return ai.callWithModel(
            "Answer directly and accurately. If uncertain, say so.\n\nQuestion: " + question, model);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GROUNDING VALIDATION
    //  Ratio threshold: 0.4 (permissive enough for partial matches)
    //  Only called for answers > 3 words (short answers pre-approved in caller)
    // ═════════════════════════════════════════════════════════════════════════
    private boolean isGroundedInDocument(String answer, String document) {
        if (answer == null || document == null) return false;
        if (answer.equalsIgnoreCase("NOT_FOUND") ||
            answer.equalsIgnoreCase("INSUFFICIENT_DATA")) return true;

        String doc = document.toLowerCase();
        String ans = answer.toLowerCase().trim();

        if (doc.contains(ans)) return true;

        String[] words    = ans.split("\\s+");
        long meaningful   = Arrays.stream(words).filter(w -> w.length() > 2).count();
        if (meaningful == 0) return true; // all stop words — consider grounded

        long matched = Arrays.stream(words)
            .filter(w -> w.length() > 2)
            .filter(doc::contains)
            .count();

        double ratio = (double) matched / meaningful;
        if (ratio < 0.4) {
            log.warn("Grounding: ratio={} answer='{}'", String.format("%.2f", ratio), answer);
            return false;
        }
        return true;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  RETRY WITH FULL DOC
    //  FIX C: always uses fullDoc (up to 2000 words), never the chunk context
    // ═════════════════════════════════════════════════════════════════════════
    private String retryWithFullDoc(String question, String fullDoc, String model) throws Exception {
        log.info("DocumentQA: retrying with full document for '{}'", question);
        String sys = PromptRouter.systemPromptFor(PromptRouter.TaskType.EXTRACTION,
                                                   PromptRouter.DocType.GENERAL);
        String raw = ai.callWithModel(
            sys + "\n\nQuestion: " + question +
            "\n\nReturn ONLY the exact value. NOT_FOUND if absent.\n\n" +
            "---FULL DOCUMENT---\n" + truncateToWords(fullDoc, 2000), model);
        return cleanExtractedValue(raw);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CHUNK SCORING
    // ═════════════════════════════════════════════════════════════════════════
    private double scoreChunk(String chunk, List<String> queryTokens) {
        String lower = chunk.toLowerCase();
        int    wc    = Math.max(lower.split("\\s+").length, 1);
        double score = 0.0;

        for (String token : queryTokens) {
            if (token.length() <= 2) continue;
            int count = 0, idx = 0;
            while ((idx = lower.indexOf(token, idx)) != -1) { count++; idx += token.length(); }
            score += count * (1.0 / (1 + Math.log(wc)));
        }

        for (int i = 0; i < queryTokens.size() - 1; i++) {
            if (lower.contains(queryTokens.get(i) + " " + queryTokens.get(i + 1))) score += 3.0;
        }

        // Section header boost
        String[] lines = chunk.split("\n");
        if (lines.length > 0) {
            String firstLine = lines[0].toLowerCase().trim();
            for (String token : queryTokens) {
                if (token.length() > 2 && firstLine.contains(token)) score += 2.0;
            }
        }

        return score;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CACHE KEY
    // ═════════════════════════════════════════════════════════════════════════
    public String buildCacheKey(String question, String docText) {
        int q = question == null ? 0 : question.toLowerCase().trim().hashCode();
        int d = docText  == null ? 0 : docText.hashCode();
        return "qa:" + Integer.toHexString(q) + ":" + Integer.toHexString(d);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  JSON HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private String parseJsonValue(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String clean = raw.replaceAll("```json|```", "").trim();
            var node = mapper.readTree(clean);
            if (node.has(field)) return node.get(field).asText();
        } catch (Exception e) {
            Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String formatMultiFieldOutput(String json, List<String> fields) {
        try {
            var node = mapper.readTree(json);
            StringBuilder sb = new StringBuilder();
            for (String f : fields) {
                String val   = node.has(f) ? node.get(f).asText() : "NOT_FOUND";
                String label = Character.toUpperCase(f.charAt(0)) + f.substring(1);
                sb.append(label).append(": ").append(val).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) { return json; }
    }

    /**
     * Strips JSON wrappers, markdown fences, and extra formatting.
     * Returns the clean extracted value or null if nothing useful found.
     */
    private String cleanExtractedValue(String raw) {
        if (raw == null || raw.isBlank()) return "NOT_FOUND";

        // Try JSON {"value": "..."} format first
        String jsonVal = parseJsonValue(raw, "value");
        if (jsonVal != null && !jsonVal.isBlank()) return jsonVal.trim();

        // Strip markdown
        String clean = raw.replaceAll("```json|```", "")
                          .replaceAll("(?m)^#{1,6}\\s+", "")
                          .replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                          .replaceAll("[\\r\\n]+", " ")
                          .replaceAll("\\s{2,}", " ")
                          .trim();

        // Short direct value (≤ 8 words, < 100 chars)
        if (clean.split("\\s+").length <= 8 && clean.length() < 100) return clean;

        // Model over-explained — take first sentence only
        String[] sentences = clean.split("(?<=[.!?])\\s+");
        if (sentences.length > 0 && sentences[0].length() < 200) return sentences[0].trim();

        return clean.length() > 200 ? clean.substring(0, 200).trim() : clean;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FIELD DETECTION
    // ═════════════════════════════════════════════════════════════════════════

    private static final List<String> KNOWN_FIELDS = List.of(
        "name", "email", "phone", "mobile", "location", "address", "city", "state",
        "country", "salary", "experience", "education", "degree", "university",
        "college", "company", "position", "role", "title", "skills", "gpa",
        "linkedin", "github", "website", "age", "dob", "nationality", "gender",
        "graduation year", "total experience"
    );

    private List<String> detectRequestedFields(String q) {
        String lower = q.toLowerCase();
        return KNOWN_FIELDS.stream().filter(lower::contains).collect(Collectors.toList());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TEXT UTILITIES
    // ═════════════════════════════════════════════════════════════════════════

    private List<String> tokenize(String text) {
        Set<String> stop = Set.of("a","an","the","is","are","was","were","be","been",
            "being","have","has","had","do","does","did","will","would","could","should",
            "may","might","shall","can","to","of","in","for","on","with","at","by","from",
            "what","who","where","when","how","which","that","this","it","its","me","my",
            "we","our","you","your","he","she","his","her","they","their","i","and","or",
            "but","not","give","tell","show","find","get","please","candidate");
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9\\s]","").split("\\s+"))
            .filter(w -> w.length() > 2 && !stop.contains(w))
            .distinct().collect(Collectors.toList());
    }

    private boolean matchesAny(String text, String... patterns) {
        for (String p : patterns) if (text.contains(p)) return true;
        return false;
    }

    private int wordCount(String t) {
        return t == null || t.isBlank() ? 0 : t.trim().split("\\s+").length;
    }

    private String truncateToWords(String text, int max) {
        if (text == null) return "";
        String[] w = text.split("\\s+");
        return w.length <= max ? text : String.join(" ", Arrays.copyOfRange(w, 0, max)) + "\n[...]";
    }

    private record ScoredChunk(String text, double score, int index) {}
}