package com.xetax.crm.ai.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingTestService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingTestService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getDimension(String text) {

        float[] vector = embeddingModel.embed(text);

        return vector.length;
    }
}
