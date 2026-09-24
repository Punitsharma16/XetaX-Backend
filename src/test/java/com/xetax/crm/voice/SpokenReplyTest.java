package com.xetax.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.xetax.crm.voice.assistant.UiAction;
import com.xetax.crm.voice.assistant.VoiceAssistant;
import com.xetax.crm.voice.tts.AzureNeuralTts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What actually reaches the speaker.
 *
 * <p>A model told to write for the ear still writes the odd asterisk or link,
 * and a synthesiser reads those out loud — "asterisk asterisk Ravi". These are
 * the last guards between the model and the user's ear.
 */
class SpokenReplyTest {

    // ── what gets stripped before it is read ────────────────────────────────

    @Test
    @DisplayName("markdown emphasis never reaches the voice")
    void stripsMarkdown() {
        assertThat(VoiceAssistant.forSpeaking("**Ravi Sharma** ka contact khol raha hoon."))
                .isEqualTo("Ravi Sharma ka contact khol raha hoon.");
    }

    @Test
    @DisplayName("a bulleted list is spoken as sentences")
    void bulletsBecomeSentences() {
        String spoken = VoiceAssistant.forSpeaking("Aaj teen meetings hain:\n- Ravi\n- Priya\n- Amit");

        assertThat(spoken).doesNotContain("-").doesNotContain("•");
        assertThat(spoken).contains("Ravi").contains("Priya").contains("Amit");
    }

    @Test
    @DisplayName("a link is not read out character by character")
    void dropsLinks() {
        assertThat(VoiceAssistant.forSpeaking("Meeting link: https://xetax.pro/m/abc-def"))
                .doesNotContain("https").doesNotContain("xetax.pro");
    }

    @Test
    @DisplayName("a code block is dropped rather than dictated")
    void dropsCodeBlocks() {
        assertThat(VoiceAssistant.forSpeaking("Here:\n```json\n{\"a\":1}\n```\nDone."))
                .doesNotContain("json").contains("Done.");
    }

    @Test
    @DisplayName("plain speech is left exactly as the model wrote it")
    void leavesPlainSpeechAlone() {
        String plain = "Ravi Sharma se last interaction teen din pehle hua tha.";
        assertThat(VoiceAssistant.forSpeaking(plain)).isEqualTo(plain);
    }

    @Test
    @DisplayName("nothing at all is still safe to hand to the synthesiser")
    void handlesNothing() {
        assertThat(VoiceAssistant.forSpeaking(null)).isEmpty();
        assertThat(VoiceAssistant.forSpeaking("   ")).isEmpty();
    }

    // ── how long the voice talks ────────────────────────────────────────────

    @Test
    @DisplayName("a long reply is cut at a sentence, never mid-word")
    void cutsAtASentence() {
        String long1 = "Pehla kaam ho gaya. Doosra kaam bhi ho gaya. Teesra abhi baaki hai.";

        String cut = AzureNeuralTts.trim(long1, 40);

        assertThat(cut).endsWith(".");
        assertThat(long1).startsWith(cut);
    }

    @Test
    @DisplayName("a reply with no sentence break is trimmed audibly, not silently")
    void marksAnAbruptCut() {
        assertThat(AzureNeuralTts.trim("a".repeat(100), 20)).endsWith("…");
    }

    @Test
    @DisplayName("a tiny opening sentence does not swallow the whole answer")
    void doesNotStopAtAnOpeningWord() {
        // "Ho gaya." then the substance — ending at that first full stop would
        // say almost nothing at all.
        String reply = "Ho gaya. " + "Ravi ke liye kal das baje follow-up task bana diya hai. ".repeat(20);

        String cut = AzureNeuralTts.trim(reply, 300);

        assertThat(cut.length()).isGreaterThan(100);
    }

    @Test
    @DisplayName("a short reply is spoken whole")
    void shortRepliesSurvive() {
        assertThat(AzureNeuralTts.trim("Ho gaya.", 600)).isEqualTo("Ho gaya.");
    }

    // ── the reply becomes XML, so it has to be escaped ──────────────────────

    @Test
    @DisplayName("a contact called Tata & Sons does not break the synthesiser")
    void escapesForSsml() {
        assertThat(AzureNeuralTts.escape("Tata & Sons")).isEqualTo("Tata &amp; Sons");
        assertThat(AzureNeuralTts.escape("a < b > c")).isEqualTo("a &lt; b &gt; c");
    }

    @Test
    @DisplayName("markup in a reply is spoken, not obeyed")
    void cannotInjectSsml() {
        // Whatever ends up in a reply — a record's name, a transcription — must
        // reach the synthesiser as words, never as instructions.
        String injected = "</voice><voice name='evil'>";

        assertThat(AzureNeuralTts.escape(injected))
                .doesNotContain("</voice>")
                .doesNotContain("<voice");
    }

    // ── what the app is allowed to be told to do ───────────────────────────

    @Test
    @DisplayName("every screen the assistant can name maps to a real route")
    void everyScreenHasARoute() {
        for (UiAction.Screen screen : UiAction.Screen.values()) {
            assertThat(screen.route())
                    .as("route for %s", screen)
                    .isNotBlank()
                    .startsWith("/");
        }
    }

    @Test
    @DisplayName("the detail screens carry the id the app substitutes")
    void detailScreensTakeAnId() {
        assertThat(UiAction.Screen.RECORD_DETAILS.route()).contains("[id]");
        assertThat(UiAction.Screen.INVOICE_DETAILS.route()).contains("[id]");
        assertThat(UiAction.Screen.CHAT.route()).contains("[id]");
    }
}
