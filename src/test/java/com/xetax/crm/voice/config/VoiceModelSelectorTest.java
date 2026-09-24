package com.xetax.crm.voice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A model name that the account does not have.
 *
 * <p>This shipped once. The configuration named a model chosen from memory
 * rather than from the account, nothing checked it, and every spoken turn in
 * production came back "404: the model does not exist or you do not have
 * access to it" — the assistant was simply mute, and the only sign was in the
 * server log. A name is now checked before it is used.
 */
class VoiceModelSelectorTest {

    /** What this account actually carries. */
    private static final Set<String> GROQ = Set.of(
            "openai/gpt-oss-20b", "openai/gpt-oss-120b", "qwen/qwen3.8-27b",
            "whisper-large-v3-turbo", "allam-2-7b");

    /** null means "leave the application's own model in place". */
    private static final String GLOBAL = null;

    @Test
    @DisplayName("nothing configured leaves the application's model alone")
    void blankUsesTheGlobalModel() {
        assertThat(VoiceModelSelector.decide("", GROQ)).isEqualTo(GLOBAL);
        assertThat(VoiceModelSelector.decide("   ", GROQ)).isEqualTo(GLOBAL);
        assertThat(VoiceModelSelector.decide(null, GROQ)).isEqualTo(GLOBAL);
    }

    @Test
    @DisplayName("a model the account has is used")
    void anAvailableModelIsUsed() {
        assertThat(VoiceModelSelector.decide("openai/gpt-oss-120b", GROQ))
                .isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    @DisplayName("a model the account does not have is refused, not shipped")
    void aMissingModelFallsBack() {
        // The exact name that broke production.
        assertThat(VoiceModelSelector.decide("llama-3.3-70b-versatile", GROQ))
                .withFailMessage("an unavailable model must never reach a spoken turn")
                .isEqualTo(GLOBAL);
    }

    @Test
    @DisplayName("stray spaces do not make a good name look bad")
    void nameIsTrimmed() {
        assertThat(VoiceModelSelector.decide("  openai/gpt-oss-20b  ", GROQ))
                .isEqualTo("openai/gpt-oss-20b");
    }

    @Test
    @DisplayName("a provider that cannot be reached is not treated as an empty catalogue")
    void unreachableProviderKeepsTheConfiguration() {
        // Not knowing is different from knowing it is absent. Overriding the
        // operator's choice because a network call failed would be worse.
        assertThat(VoiceModelSelector.decide("openai/gpt-oss-120b", Set.of()))
                .isEqualTo("openai/gpt-oss-120b");
    }
}
