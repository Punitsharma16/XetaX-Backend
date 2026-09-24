package com.xetax.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.voice.config.VoiceModelSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which model the voice assistant ends up on.
 *
 * <p>A model name that the account does not carry is accepted silently by
 * configuration and then rejected by the provider on every single turn — the
 * assistant hears you, thinks, and answers nothing, over and over. It happened
 * in production with a name that simply does not exist on this Groq key. The
 * name is now checked once, at startup, and a bad one loses to the model the
 * rest of the application already runs on.
 */
class VoiceModelChoiceTest {

    /** No key configured, so the provider is never asked — the offline path. */
    private final VoiceModelSelector selector =
            new VoiceModelSelector(new VoiceProperties(), new ObjectMapper());

    @Test
    @DisplayName("blank leaves the application's own model in place")
    void blankKeepsTheGlobalModel() {
        assertThat(selector.choose("")).isNull();
        assertThat(selector.choose("   ")).isNull();
        assertThat(selector.choose(null)).isNull();
    }

    @Test
    @DisplayName("a configured name is not thrown away when it cannot be checked")
    void anUncheckableNameIsStillHonoured() {
        // Nothing to verify against here. Overriding the operator's choice on a
        // failed lookup would be worse than trusting it.
        assertThat(selector.choose("openai/gpt-oss-120b")).isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    @DisplayName("surrounding whitespace in the setting is not part of the name")
    void theNameIsTrimmed() {
        assertThat(selector.choose("  openai/gpt-oss-20b  ")).isEqualTo("openai/gpt-oss-20b");
    }
}
