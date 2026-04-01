package com.velora.aijobflow.worker;

import com.velora.aijobflow.model.Job;
import com.velora.aijobflow.repository.JobRepository;
import com.velora.aijobflow.service.JobService;
import com.velora.aijobflow.util.DocumentQAEngine;
import com.velora.aijobflow.util.OpenAIClient;
import com.velora.aijobflow.util.PdfTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobWorkerConsumer {

    private final JobRepository    jobRepository;
    private final JobService       jobService;
    private final OpenAIClient     openAIClient;
    private final DocumentQAEngine qaEngine;
    private final PdfTextExtractor pdfExtractor;

    // Maps frontend label values to real Groq model IDs.
    private static final String MODEL_FAST         = "llama-3.1-8b-instant";
    private static final String MODEL_BALANCED     = "llama-3.3-70b-versatile";
    private static final String MODEL_BEST_QUALITY = "llama-3.1-70b-versatile";

    // Humanizing prompt — wraps raw extracted values into natural sentences.
    // Used ONLY for short QA answers not already humanized by QAEngine.
    private static final String HUMANIZE_SYSTEM =
        "You are a premium SaaS output formatter.\n" +
        "Rewrite the raw answer as one clean, natural, complete sentence.\n" +
        "RULES:\n" +
        "- ALWAYS wrap in a meaningful sentence.\n" +
        "- NEVER return a raw value alone.\n" +
        "- NEVER use bullet points, markdown, or JSON.\n" +
        "- NEVER say 'based on the provided text'.\n" +
        "- DO NOT repeat the question.\n" +
        "- 1-2 sentences maximum.\n" +
        "- If answer is NOT_FOUND → write: 'This information is not mentioned in the document.'\n\n" +
        "EXAMPLES:\n" +
        "Raw: 'Pune'       → 'The candidate is based in Pune.'\n" +
        "Raw: '3 years'    → 'The candidate has 3 years of work experience.'\n" +
        "Raw: 'NOT_FOUND'  → 'This information is not mentioned in the document.'";

    @RabbitListener(queues = "${queue.job.name}")
    public void processJob(Job job) {
        String model = resolveModelName(job.getAiModel());
        log.info("Job received: id={} type={} model={} hasDoc={}",
                 job.getId(), job.getJobType(), model,
                 job.getDocumentText() != null && !job.getDocumentText().isBlank());
        long start = System.currentTimeMillis();

        try {
            Job current = jobRepository.findById(job.getId()).orElseThrow();
            current.setStatus("PROCESSING");
            jobRepository.save(current);

            String result  = dispatch(job);
            long   elapsed = System.currentTimeMillis() - start;
            jobService.completeJob(job.getId(), result, elapsed);
            log.info("Job {} COMPLETED in {}ms", job.getId(), elapsed);

        } catch (Exception e) {
            log.error("Job {} FAILED: {}", job.getId(), e.getMessage(), e);
            jobService.failJob(job.getId(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DISPATCH — routes job type to the correct AI method
    //
    //  Content resolution priority:
    //    docText  = uploaded PDF text (extracted at upload time, or re-extracted here)
    //    payload  = text typed by user in the input box
    //    content  = docText if present, else payload (for doc-based jobs)
    //
    //  Job routing decision:
    //    DOCUMENT JOBS (use content = docText or payload):
    //      AI_ANALYSIS, SUMMARIZE, SENTIMENT, EXTRACT_KEYWORDS, RESUME_SCORE,
    //      LINKEDIN_BIO, COMPARE_DOCUMENTS — benefit from PDF if uploaded
    //
    //    TEXT JOBS (always use payload, regardless of PDF):
    //      TRANSLATE, CODE_REVIEW, CLASSIFY, EMAIL_WRITER, BUG_EXPLAINER,
    //      INTERVIEW_PREP, GENERATE_REPORT — user typed the input
    //
    //    SPECIAL ROUTING:
    //      QUESTION_ANSWER — payload = question, docText = document (never merged)
    //      JD_MATCH — payload = JD text OR combined resume+JD; docText = resume PDF
    // ═══════════════════════════════════════════════════════════════════════
    private String dispatch(Job job) throws Exception {
        String model   = resolveModelName(job.getAiModel());
        String payload = job.getPayload() != null ? job.getPayload().trim() : "";

        // Resolve document text — stored at upload > re-extract from file > null
        String docText = job.getDocumentText();
        if ((docText == null || docText.isBlank()) &&
             job.getFilePath() != null && !job.getFilePath().isBlank()) {
            try {
                docText = pdfExtractor.extract(job.getFilePath());
                log.info("Job {}: re-extracted {} chars from file", job.getId(), docText.length());
            } catch (Exception e) {
                log.warn("Job {}: file re-extraction failed: {}", job.getId(), e.getMessage());
            }
        }

        // content = for doc-based jobs (uses PDF when available, falls back to payload)
        String content = (docText != null && !docText.isBlank()) ? docText : payload;

        log.info("Job {} dispatch: type={} model={} payloadLen={} docLen={} source={}",
                 job.getId(), job.getJobType(), model, payload.length(),
                 docText != null ? docText.length() : 0,
                 docText != null && !docText.isBlank() ? "DOCUMENT" : "PAYLOAD");

        return switch (job.getJobType().toUpperCase()) {

            // ── DOCUMENT JOBS — use content (docText or payload) ──────────────
            case "AI_ANALYSIS"       -> openAIClient.analyzeText(content, model);
            case "SUMMARIZE"         -> openAIClient.summarizeText(content, model);
            case "SENTIMENT"         -> openAIClient.analyzeSentiment(content, model);
            case "EXTRACT_KEYWORDS"  -> openAIClient.extractKeywords(content, model);
            case "RESUME_SCORE"      -> openAIClient.scoreResume(content, model);
            case "LINKEDIN_BIO"      -> openAIClient.generateLinkedInBio(content, model);
            case "COMPARE_DOCUMENTS" -> openAIClient.compareDocuments(content, model);

            // ── TEXT JOBS — always use payload (user typed it) ────────────────
            case "TRANSLATE"         -> openAIClient.translateText(payload, model);
            case "CODE_REVIEW"       -> openAIClient.reviewCode(payload, model);
            case "CLASSIFY"          -> openAIClient.classifyText(payload, model);
            case "EMAIL_WRITER"      -> openAIClient.writeEmail(payload, model);
            case "BUG_EXPLAINER"     -> openAIClient.explainBug(payload, model);
            case "GENERATE_REPORT"   -> openAIClient.generateReport(payload, model);
            case "MEETING_SUMMARY"   -> openAIClient.summarizeMeeting(payload, model);
            case "INTERVIEW_PREP"    -> openAIClient.generateInterviewPrep(payload, model);

            // ── QUESTION_ANSWER — payload = question, docText = document ──────
            // CRITICAL: question and document MUST NEVER be merged into one string.
            // If merged, the AI cannot tell where the question ends and doc begins.
            case "QUESTION_ANSWER", "QA" -> {
                String raw;
                if (docText != null && !docText.isBlank()) {
                    // Has document — use QA engine (RAG pipeline)
                    raw = qaEngine.answer(payload, docText, model);
                } else {
                    // No document — general knowledge Q&A
                    raw = openAIClient.answerQuestions(payload, model);
                }
                // Humanize only if QAEngine returned a raw value (not already a sentence)
                yield humanizeIfNeeded(raw, payload, model);
            }

            // ── JD_MATCH — special routing ────────────────────────────────────
            // payload = JD text typed by user (or combined resume+JD if pasted together)
            // docText = resume PDF (if user uploaded one separately)
            // The method receives payload containing JD, and resume separately.
            case "JD_MATCH" -> openAIClient.matchJobDescription(payload, model, content);

            default -> {
                log.warn("Unknown job type '{}' — using generic analysis", job.getJobType());
                yield openAIClient.callWithModel(
                    "Analyze the following and provide insights:\n\n" + content, model);
            }
        };
    }

    // ── HUMANIZE (only for QA) ────────────────────────────────────────────────
    // If the QA engine already returned a complete sentence, skip.
    // If it returned a raw value ("Pune", "3 years"), wrap it into a sentence.
    private String humanizeIfNeeded(String rawAnswer, String originalQuestion, String model) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "This information could not be retrieved from the provided document.";
        }
        String trimmed = rawAnswer.trim();

        // Already a well-formed sentence — skip humanization to avoid double-wrapping
        if (trimmed.split("\\s+").length >= 5 && trimmed.matches(".*[.!?]$")) {
            return trimmed;
        }

        // Very short or garbage — return friendly not-found message
        if (trimmed.length() < 2) {
            return "This information is not mentioned in the document.";
        }

        try {
            String userPrompt =
                "Question asked: " + originalQuestion + "\n" +
                "Raw answer: " + rawAnswer + "\n\n" +
                "Rewrite as one complete sentence:";
            return openAIClient.callWithModel(HUMANIZE_SYSTEM + "\n\n" + userPrompt, model);
        } catch (Exception e) {
            log.warn("Humanization failed — returning raw: {}", e.getMessage());
            return rawAnswer;
        }
    }

    // ── MODEL RESOLUTION ──────────────────────────────────────────────────────
    // Maps frontend labels ("Fast", "Balanced", "Best Quality") to real Groq model IDs.
    // Also handles the case where the real model string is already sent.
    private String resolveModelName(String modelInput) {
        if (modelInput == null || modelInput.isBlank()) return MODEL_BALANCED;
        return switch (modelInput.toLowerCase().trim()) {
            case "fast"                       -> MODEL_FAST;
            case "balanced"                   -> MODEL_BALANCED;
            case "best quality", "best"       -> MODEL_BEST_QUALITY;
            case "llama-3.1-8b-instant"       -> MODEL_FAST;
            case "llama-3.3-70b-versatile"    -> MODEL_BALANCED;
            case "llama-3.1-70b-versatile"    -> MODEL_BEST_QUALITY;
            default -> {
                log.warn("Unknown model '{}' — defaulting to balanced", modelInput);
                yield MODEL_BALANCED;
            }
        };
    }
}