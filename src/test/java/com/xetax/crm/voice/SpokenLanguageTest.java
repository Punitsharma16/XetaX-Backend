package com.xetax.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.xetax.crm.voice.stt.GroqWhisperStt;
import com.xetax.crm.voice.stt.SpokenLanguage;
import com.xetax.crm.voice.tts.VoiceCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The language the answer comes back in.
 *
 * <p>A user said "hlo hlo hlo hlo" and got an answer in Urdu script. Hindi and
 * Urdu are the same spoken language written two ways, the recogniser picks
 * between them more or less at random, and four filler words gave it nothing
 * to go on — so the reply was not merely in the wrong language, it was in a
 * script the person could not read.
 *
 * <p>The assistant now speaks two languages, Hindi and English, and the
 * Hinglish mix of them that people actually use. Every other guess the
 * recogniser can make has to land on one of those two.
 */
class SpokenLanguageTest {

    private static final String DEFAULT = "hi";

    @DisplayName("everything that is not English is answered in Hindi")
    @ParameterizedTest(name = "\"{0}\" -> hi")
    @ValueSource(strings = { "ur", "hi", "pa", "mr", "bn", "ne", "sa", "ta", "xx" })
    void anythingElseBecomesHindi(String detected) {
        assertThat(SpokenLanguage.normalise(detected)).isEqualTo("hi");
    }

    @DisplayName("English stays English, however it is reported")
    @ParameterizedTest(name = "\"{0}\" -> en")
    @ValueSource(strings = { "en", "EN", "eng", "en-IN", " en " })
    void englishStaysEnglish(String detected) {
        assertThat(SpokenLanguage.normalise(detected)).isEqualTo("en");
    }

    @Test
    @DisplayName("Urdu is answered as Hindi — the same words, a readable script")
    void urduBecomesHindi() {
        assertThat(SpokenLanguage.settle("ur", "mujhe aaj ke task dikhao abhi", null, DEFAULT))
                .isEqualTo("hi");
    }

    @Test
    @DisplayName("a couple of words do not change the conversation's language")
    void shortRepliesCarryTheConversationOn() {
        // Mid-conversation in English, the user says "haan". Answering that in
        // Hindi because two syllables sounded Hindi is worse than carrying on.
        assertThat(SpokenLanguage.settle("hi", "haan", "en", DEFAULT)).isEqualTo("en");
        assertThat(SpokenLanguage.settle("ur", "ok", "en", DEFAULT)).isEqualTo("en");
    }

    @Test
    @DisplayName("the very first words, if there are few, use the account's language")
    void aShortOpenerFallsBackToTheDefault() {
        // This is the exact case from the screenshot.
        assertThat(SpokenLanguage.settle("ur", "hlo hlo hlo", null, DEFAULT))
                .withFailMessage("four filler words must not decide the language")
                .isEqualTo("hi");
    }

    @Test
    @DisplayName("a whole sentence is believed, and can switch the conversation")
    void aFullSentenceIsTrusted() {
        assertThat(SpokenLanguage.settle("en", "show me the sales pipeline records", "hi", DEFAULT))
                .isEqualTo("en");
        assertThat(SpokenLanguage.settle("hi", "mujhe aaj ke saare task dikhao", "en", DEFAULT))
                .isEqualTo("hi");
    }

    @Test
    @DisplayName("nothing at all still yields a language")
    void neverReturnsNothing() {
        assertThat(SpokenLanguage.settle(null, null, null, DEFAULT)).isEqualTo("hi");
        assertThat(SpokenLanguage.settle("", "", null, "en")).isEqualTo("en");
    }

    @DisplayName("each language is read by an Indian voice")
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({ "hi, hi-IN-SwaraNeural", "en, en-IN-NeerjaNeural", "ur, hi-IN-SwaraNeural" })
    void theVoiceFollowsTheLanguage(String code, String voice) {
        assertThat(VoiceCatalog.voiceFor(code)).isEqualTo(voice);
    }

    @Test
    @DisplayName("a missing language still gets a voice rather than silence")
    void unknownStillSpeaks() {
        assertThat(VoiceCatalog.voiceFor(null)).isEqualTo("hi-IN-SwaraNeural");
        assertThat(VoiceCatalog.voiceFor("")).isEqualTo("hi-IN-SwaraNeural");
    }

    @Test
    @DisplayName("the spoken locale matches the voice, so words are pronounced right")
    void localeFollowsTheVoice() {
        assertThat(VoiceCatalog.localeOf("hi-IN-SwaraNeural")).isEqualTo("hi-IN");
        assertThat(VoiceCatalog.localeOf("en-IN-NeerjaNeural")).isEqualTo("en-IN");
    }

    @Test
    @DisplayName("the recogniser's language name still becomes a code first")
    void namesBecomeCodes() {
        assertThat(GroqWhisperStt.toCode("english")).isEqualTo("en");
        assertThat(GroqWhisperStt.toCode("hindi")).isEqualTo("hi");
        assertThat(GroqWhisperStt.toCode("urdu")).isEqualTo("ur");
        assertThat(GroqWhisperStt.toCode(null)).isEqualTo("en");
    }
}
