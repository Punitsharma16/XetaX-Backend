package com.xetax.crm.ai;

import com.xetax.crm.ai.config.KnowledgeService;
import com.xetax.crm.ai.rag.ChunkingService;
import com.xetax.crm.ai.rag.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The knowledge base is an extra the assistant reads before answering; the
 * answers themselves come from the CRM's own data through the assistant's
 * tools. While the vector store was unreachable, every message — even "hi" —
 * came back as 503 and the panel showed the question with no reply at all.
 */
class KnowledgeOutageTest {

    private RagService ragWithStore(VectorStore store) {
        return new RagService(new KnowledgeService(store, mock(ChunkingService.class)));
    }

    @Test
    void anUnreachableVectorStoreLeavesTheAssistantAnswering() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("Connection refused: localhost/127.0.0.1:6334"));

        String context = ragWithStore(store).retrieveContext("hi", UUID.randomUUID());

        assertTrue(context.contains("NO_RELEVANT_KNOWLEDGE_FOUND"), context);
    }

    @Test
    void aStoreThatAnswersNothingIsTreatedTheSameWay() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(null);

        String context = ragWithStore(store).retrieveContext("hi", UUID.randomUUID());

        assertTrue(context.contains("NO_RELEVANT_KNOWLEDGE_FOUND"), context);
    }

    @Test
    void searchReturnsAnEmptyListRatherThanThrowing() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("collection xetax-knowledge not found"));

        var found = new KnowledgeService(store, mock(ChunkingService.class))
                .searchKnowledge("anything", UUID.randomUUID(), 3);

        assertEquals(0, found.size());
    }
}
