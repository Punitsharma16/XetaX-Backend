package com.xetax.crm.ai.model;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeMetadata (
        KnowledgeType knowledgeType,
        // Auth user ids are UUIDs (AuthUserEntity.id) — there is no numeric
        // user id anywhere in the system.
        UUID userId,
        String module,
        String entityId,
        String source,
        String version,
        Instant updatedAt,
        String knowledgeId

)  {
}
