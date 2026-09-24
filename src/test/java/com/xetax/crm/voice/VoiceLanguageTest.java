package com.xetax.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.xetax.crm.voice.stt.GroqWhisperStt;
import com.xetax.crm.voice.tts.VoiceCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The assistant answers in the language it was spoken to in.
 *
 * <p>This is the chain that makes that true: the recogniser names the language,
 * the name becomes a code, the code picks the voice. Break any link and a Hindi
 * question comes back read aloud by an English voice — which is exactly what
 * makes a voice assistant feel broken rather than imperfect.
 */
class VoiceLanguageTest {

    @DisplayName("the recogniser's language name becomes a code")
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "english, en",
            "hindi, hi",
            "marathi, mr",
            "gujarati, gu",
            "tamil, ta",
            "punjabi, pa",
            "urdu, ur",
            "HINDI, hi",
            "' hindi ', hi",
    })
    void namesBecomeCodes(String reported, String code) {
        assertThat(GroqWhisperStt.toCode(reported)).isEqualTo(code);
    }

    @Test
    @DisplayName("a code that arrives as a code is left alone")
    void codesPassThrough() {
        assertThat(GroqWhisperStt.toCode("hi")).isEqualTo("hi");
        assertThat(GroqWhisperStt.toCode("en")).isEqualTo("en");
    }

    @Test
    @DisplayName("silence is treated as English rather than as nothing")
    void blankFallsBack() {
        assertThat(GroqWhisperStt.toCode("")).isEqualTo("en");
        assertThat(GroqWhisperStt.toCode(null)).isEqualTo("en");
    }

    @Test
    @DisplayName("a language nobody mapped still yields something usable")
    void unknownLanguageDegrades() {
        assertThat(GroqWhisperStt.toCode("swahili")).isEqualTo("sw");
    }

    @DisplayName("each language is read by a voice of its own")
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "hi, hi-IN-SwaraNeural",
            "mr, mr-IN-AarohiNeural",
            "ta, ta-IN-PallaviNeural",
            "bn, bn-IN-TanishaaNeural",
            "ur, ur-IN-GulNeural",
    })
    void eachLanguageHasItsOwnVoice(String code, String voice) {
        assertThat(VoiceCatalog.voiceFor(code)).isEqualTo(voice);
    }

    @Test
    @DisplayName("English is the Indian one — that is the accent these users read in")
    void englishIsIndian() {
        assertThat(VoiceCatalog.voiceFor("en")).isEqualTo("en-IN-NeerjaNeural");
    }

    @Test
    @DisplayName("Punjabi falls back to Hindi, not to English")
    void punjabiFallsBackToHindi() {
        // There is no Punjabi neural voice on this tier. Hindi is far closer to
        // understandable for that listener than English would be.
        assertThat(VoiceCatalog.voiceFor("pa")).isEqualTo("hi-IN-SwaraNeural");
    }

    @Test
    @DisplayName("an unknown language still gets a voice rather than silence")
    void unknownLanguageStillSpeaks() {
        assertThat(VoiceCatalog.voiceFor("xx")).isEqualTo("en-IN-NeerjaNeural");
        assertThat(VoiceCatalog.voiceFor(null)).isEqualTo("en-IN-NeerjaNeural");
        assertThat(VoiceCatalog.voiceFor("")).isEqualTo("en-IN-NeerjaNeural");
    }

    @Test
    @DisplayName("the spoken locale matches the voice, so the words are pronounced right")
    void localeFollowsTheVoice() {
        assertThat(VoiceCatalog.localeOf("hi-IN-SwaraNeural")).isEqualTo("hi-IN");
        assertThat(VoiceCatalog.localeOf("en-IN-NeerjaNeural")).isEqualTo("en-IN");
        assertThat(VoiceCatalog.localeOf("zh-CN-XiaoxiaoNeural")).isEqualTo("zh-CN");
    }
}
