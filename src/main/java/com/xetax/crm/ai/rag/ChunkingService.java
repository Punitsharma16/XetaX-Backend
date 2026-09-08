package com.xetax.crm.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;


@Service
public class ChunkingService {

    private final TokenTextSplitter splitter;

    /*
     * No TokenTextSplitter bean exists (and the old constructor parameter was
     * never used — the splitter is built right here), so injecting it made the
     * whole application fail to boot. Same chunking settings as before.
     */
    public ChunkingService(){

        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(20)
                .withKeepSeparator(true)
                .build();
    }

    public List<Document> chunkText(Document document) {
        return splitter.apply(List.of(document));
    }
}
