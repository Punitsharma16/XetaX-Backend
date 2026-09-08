package com.xetax.crm.ai.config;

import com.xetax.crm.ai.model.KnowledgeMetadata;
import com.xetax.crm.ai.rag.ChunkingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
public class KnowledgeService {

    private final VectorStore vectorStore;
    private final ChunkingService chunkingService;

    public KnowledgeService(VectorStore vectorStore, ChunkingService chunkingService) {
        this.vectorStore = vectorStore;
        this.chunkingService = chunkingService;
    }

    public void addKnowledge(String content, KnowledgeMetadata metadata) {

        Map<String, Object> metadataMap = new HashMap<>();

        metadataMap.put(
                "knowledgeType",
                metadata.knowledgeType().name()
        );

        metadataMap.put(
                "userId",
                metadata.userId() != null
                        ? metadata.userId().toString()
                        : "GLOBAL"
        );

        metadataMap.put(
                "module",
                metadata.module()
        );

        metadataMap.put(
                "entityId",
                metadata.entityId() != null
                        ? metadata.entityId()
                        : ""
        );

        metadataMap.put(
                "source",
                metadata.source()
        );

        metadataMap.put(
                "version",
                metadata.version()
        );

        metadataMap.put(
                "updatedAt",
                metadata.updatedAt().toString()
        );

        metadataMap.put(
                "knowledgeId",
                metadata.knowledgeId()
        );

        Document document = new Document(
                content,
                metadataMap
        );

        List<Document> chunks = chunkingService.chunkText(document);

        vectorStore.add(chunks);
    }

    /*
     * User isolation happens here: USER-typed chunks are only visible when
     * their userId metadata matches the authenticated user's UUID; GLOBAL
     * chunks are visible to everyone (their userId metadata is the literal
     * "GLOBAL", see addKnowledge). Without a userId only GLOBAL is served.
     *
     * The expression is deliberately a single IN over the userId key: the
     * nested form  "type == 'GLOBAL' OR (type == 'USER' AND userId == ...)"
     * was verified to leak other users' chunks through the vector-store
     * filter conversion, while a single-condition IN cannot be mis-combined.
     */
    public List<Document> searchKnowledge(String query, java.util.UUID userId, int topK) {

        String filterExpression = userId != null
                ? String.format("userId IN ['GLOBAL', '%s']", userId)
                : "userId == 'GLOBAL'";

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterExpression)
                .similarityThreshold(0.30)
                .build();

        return vectorStore.similaritySearch(request);
    }

    public void deleteKnowledge(String knowledgeId) {

        Filter.Expression filterExpression =  new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("knowledgeId"),
                new Filter.Value(knowledgeId)
        );

        vectorStore.delete(filterExpression);

    }
}
