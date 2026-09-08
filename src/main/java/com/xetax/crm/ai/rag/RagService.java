package com.xetax.crm.ai.rag;

import com.xetax.crm.ai.config.KnowledgeService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RagService {

    KnowledgeService knowledgeService;

    public RagService(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    public String retrieveContext(String query , java.util.UUID userId) {

        // topK 3: the closest chunks carry the answer; fewer chunks keep the
        // per-request token cost down without losing relevant context.
        List<Document> documents = knowledgeService.searchKnowledge(query , userId , 3);
        return buildContext(documents);
    }

    /*
     * Structured context block. Clear delimiters mark where retrieved DATA
     * starts and ends so the model can treat it as data, never as
     * instructions; the system prompt references these exact markers.
     */
    public String buildContext(List<Document> documents) {

        StringBuilder context = new StringBuilder();
        context.append("XETAX CRM RETRIEVED KNOWLEDGE\n");

        if (documents == null || documents.isEmpty()) {
            context.append("NO_RELEVANT_KNOWLEDGE_FOUND\n");
            context.append("END OF RETRIEVED KNOWLEDGE");
            return context.toString();
        }

        int index = 1;
        for (Document document : documents) {

            Map<String, Object> metadata =
                    document.getMetadata();

            context.append("\n[Knowledge ")
                    .append(index++)
                    .append("]\n");

            context.append("Module: ")
                    .append(metadata.get("module"))
                    .append("\n");

            context.append("Source: ")
                    .append(metadata.get("source"))
                    .append("\n");

            context.append("Knowledge ID: ")
                    .append(metadata.get("knowledgeId"))
                    .append("\n");

            context.append("Content:\n")
                    .append(document.getText())
                    .append("\n");

            context.append("---\n");
        }

        context.append("\nEND OF RETRIEVED KNOWLEDGE");

        return context.toString();
    }
}
