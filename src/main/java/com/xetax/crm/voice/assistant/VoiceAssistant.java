package com.xetax.crm.voice.assistant;

import com.xetax.crm.ai.rag.RagService;
import com.xetax.crm.voice.tools.NavigationTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * One spoken turn: words in, words out, and possibly a screen to open.
 *
 * <p>The language the user spoke is passed to the model as data rather than
 * left for it to guess. On a five-word utterance that guess is unreliable, and
 * getting it wrong means the whole reply comes back in the wrong language and
 * is then read aloud by the wrong voice — the most obvious way this feature
 * can feel broken.
 */
@Service
@Slf4j
public class VoiceAssistant {

    private final ChatClient voiceChatClient;
    private final NavigationTools navigationTools;
    private final RagService ragService;
    private final boolean ragEnabled;

    public VoiceAssistant(@Qualifier("voiceChatClient") ChatClient voiceChatClient,
                          NavigationTools navigationTools,
                          RagService ragService,
                          @Value("${voice.rag-enabled:true}") boolean ragEnabled) {
        this.voiceChatClient = voiceChatClient;
        this.navigationTools = navigationTools;
        this.ragService = ragService;
        this.ragEnabled = ragEnabled;
    }

    /**
     * @param conversationId scopes the memory — one per assistant session, per user
     * @param spoken         what the user said
     * @param language       the code the recogniser reported
     * @param userId         the data owner, from the verified token, never from the model
     */
    public VoiceReply answer(String conversationId, String spoken, String language, UUID userId) {
        // A pooled thread may still be holding the previous turn's pick.
        navigationTools.clear();

        String prompt = buildPrompt(spoken, language, userId);

        try {
            String text = voiceChatClient.prompt()
                    .user(prompt)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
            return new VoiceReply(forSpeaking(text), language, navigationTools.takePending());
        } finally {
            // A turn that threw half-way may have left a pick behind, and this
            // thread goes back to a pool.
            navigationTools.clear();
        }
    }

    private String buildPrompt(String spoken, String language, UUID userId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[spoken language: ").append(language == null ? "en" : language).append("]\n");

        if (ragEnabled) {
            try {
                String context = ragService.retrieveContext(spoken, userId);
                if (context != null && !context.isBlank()) {
                    prompt.append(context).append('\n');
                }
            } catch (Exception e) {
                // Product knowledge is a bonus here; the tools are the substance.
                log.debug("Voice turn without retrieved knowledge: {}", e.getMessage());
            }
        }

        prompt.append("\nThe user said out loud:\n").append(spoken);
        return prompt.toString();
    }

    /**
     * The model is told not to write markdown, and mostly obeys. What slips
     * through would be read out as "asterisk" or "hash", so it is stripped
     * rather than trusted away.
     */
    public static String forSpeaking(String text) {
        if (text == null) return "";
        return text
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("[*_`#>]", "")
                .replaceAll("^\\s*[-•]\\s*", "")
                .replaceAll("\\n\\s*[-•]\\s*", ". ")
                .replaceAll("https?://\\S+", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }
}
