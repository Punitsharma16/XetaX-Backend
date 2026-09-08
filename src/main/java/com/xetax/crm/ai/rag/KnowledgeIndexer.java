package com.xetax.crm.ai.rag;


import com.xetax.crm.ai.config.KnowledgeService;
import com.xetax.crm.ai.model.KnowledgeMetadata;
import com.xetax.crm.ai.model.KnowledgeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class KnowledgeIndexer {

    private static final String SOURCE_CRM = "crm";

    private final KnowledgeService knowledgeService;

    public KnowledgeIndexer(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    // ------------------------------------------------------ CRM entity hooks

    /*
     * Lifecycle entry points the CRM services call after their own database
     * operation has succeeded. The database stays the source of truth: every
     * failure here is logged and swallowed so a Qdrant/embedding outage can
     * never fail or roll back a CRM request.
     *
     * The entity hooks run ASYNC on the single-threaded indexingExecutor:
     * the caller has already built the content string, so the CRM write
     * returns immediately while embedding + Qdrant I/O happen in the
     * background, in submission order.
     *
     * knowledgeId strategy: "{module}:{entityId}" — deterministic, so a
     * reindex/delete always targets exactly the chunks of that one entity and
     * never another user's knowledge.
     */

    @Async("indexingExecutor")
    public void indexEntity(String module, Long entityId, String content, UUID userId) {
        try {
            index(content, entityMetadata(module, entityId, userId));
        } catch (Exception e) {
            log.error("Knowledge indexing failed for {}:{} — {}", module, entityId, e.getMessage());
        }
    }

    @Async("indexingExecutor")
    public void reindexEntity(String module, Long entityId, String content, UUID userId) {
        try {
            reindex(content, entityMetadata(module, entityId, userId));
        } catch (Exception e) {
            log.error("Knowledge reindexing failed for {}:{} — {}", module, entityId, e.getMessage());
        }
    }

    @Async("indexingExecutor")
    public void deleteEntity(String module, Long entityId) {
        try {
            delete(knowledgeIdFor(module, entityId));
        } catch (Exception e) {
            log.error("Knowledge deletion failed for {}:{} — {}", module, entityId, e.getMessage());
        }
    }

    public static String knowledgeIdFor(String module, Long entityId) {
        return module + ":" + entityId;
    }

    private KnowledgeMetadata entityMetadata(String module, Long entityId, UUID userId) {
        Instant now = Instant.now();
        return new KnowledgeMetadata(
                userId != null ? KnowledgeType.USER : KnowledgeType.GLOBAL,
                userId,
                module,
                String.valueOf(entityId),
                SOURCE_CRM,
                String.valueOf(now.toEpochMilli()),
                now,
                knowledgeIdFor(module, entityId)
        );
    }

    // ------------------------------------------------------ generic lifecycle

    public void index(String content, KnowledgeMetadata metadata) {
        knowledgeService.addKnowledge(
                content,
                metadata
        );
    }

    public void delete(String knowledgeId) {

        knowledgeService.deleteKnowledge(
                knowledgeId
        );
    }

    public void reindex(String content, KnowledgeMetadata metadata) {
        delete(metadata.knowledgeId());
        index(content, metadata);
    }
}
