package com.xetax.crm.agent.service;

import com.xetax.crm.agent.entity.AgentSource;
import com.xetax.crm.ai.rag.ChunkingService;
import com.xetax.crm.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent knowledge lives in the SAME Qdrant collection as CRM knowledge but in
 * a disjoint metadata namespace: agent chunks carry {agentId, sourceId} and
 * no userId, CRM chunks carry userId and no agentId — each side's filter can
 * never see the other's documents.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentKnowledgeService {

    private static final int MAX_CONTENT_CHARS = 400_000;

    private final VectorStore vectorStore;
    private final ChunkingService chunkingService;

    /* --------------------------------------------------------- extraction */

    public String extractPdf(byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(pdf);
            if (text == null || text.isBlank()) {
                throw new BadRequestException(
                        "PDF me koi text nahi mila — scanned/image PDF abhi supported nahi hai");
            }
            return text;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("PDF padha nahi ja saka: " + e.getMessage());
        }
    }

    public String extractUrl(String url) {
        try {
            var page = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (XetaX AgentBot)")
                    .timeout(15000)
                    .get();
            page.select("script, style, nav, footer, header, noscript, svg, form").remove();
            String text = (page.title() + "\n\n" + page.body().text()).trim();
            if (text.length() < 50) {
                throw new BadRequestException(
                        "Page se kaafi text nahi mila (JavaScript-rendered site ho sakti hai) — "
                        + "content copy karke Manual text se add karo");
            }
            return text;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("URL fetch fail: " + e.getMessage());
        }
    }

    /* ----------------------------------------------------------- indexing */

    /** Chunks + embeds one source's text. Returns the chunk count. */
    public int index(Long agentId, AgentSource source, String text) {
        String content = text.length() > MAX_CONTENT_CHARS
                ? text.substring(0, MAX_CONTENT_CHARS) : text;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agentId", String.valueOf(agentId));
        metadata.put("sourceId", String.valueOf(source.getId()));
        metadata.put("sourceName", source.getName());
        metadata.put("knowledgeId", "agent-source:" + source.getId());

        List<Document> chunks = chunkingService.chunkText(new Document(content, metadata));
        // idempotent: re-index of the same source replaces its old chunks
        deleteSource(source.getId());
        vectorStore.add(chunks);
        return chunks.size();
    }

    public void deleteSource(Long sourceId) {
        try {
            var b = new FilterExpressionBuilder();
            vectorStore.delete(b.eq("sourceId", String.valueOf(sourceId)).build());
        } catch (Exception e) {
            log.warn("Agent source {} vector delete failed: {}", sourceId, e.getMessage());
        }
    }

    public void deleteAgent(Long agentId) {
        try {
            var b = new FilterExpressionBuilder();
            vectorStore.delete(b.eq("agentId", String.valueOf(agentId)).build());
        } catch (Exception e) {
            log.warn("Agent {} vector delete failed: {}", agentId, e.getMessage());
        }
    }

    /** Retrieval for the public chat — ONLY this agent's chunks. */
    public List<Document> search(Long agentId, String query) {
        var b = new FilterExpressionBuilder();
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(4)
                .similarityThreshold(0.25)
                .filterExpression(b.eq("agentId", String.valueOf(agentId)).build())
                .build());
    }
}
