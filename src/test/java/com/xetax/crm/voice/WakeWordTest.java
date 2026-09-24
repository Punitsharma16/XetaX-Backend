package com.xetax.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.xetax.crm.voice.socket.WakeWord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * When the assistant is being spoken to, and when it is only overhearing.
 *
 * <p>Both halves matter equally. Missing the phrase makes the assistant look
 * broken; waking to ordinary conversation makes it run tools, speak over
 * people and spend their AI credits on words that were never meant for it.
 */
class WakeWordTest {

    @Nested
    @DisplayName("it wakes")
    class ItWakes {

        @DisplayName("to the phrase, however the recogniser spelled it")
        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "Hey XetaX", "hey xeta", "Hi Xeta", "hello xetax", "Ok Zeta",
                "hey zeta", "hey cheetah", "hi cheeta", "hey jeta", "ok zeeta",
                "Hey, XetaX!", "hey  xeta", "XetaX", "xeta",
                "हे ज़ेटा", "हैलो जेटा", "ہے زیٹا",
        })
        void toTheNameHoweverItIsHeard(String said) {
            assertThat(WakeWord.find(said))
                    .withFailMessage("should have woken to \"%s\"", said)
                    .isNotNull();
        }

        @Test
        @DisplayName("and knows nothing was asked yet")
        void nameAloneIsNotACommand() {
            assertThat(WakeWord.find("hey xeta").hasCommand()).isFalse();
        }
    }

    @Nested
    @DisplayName("it does not wake")
    class ItDoesNotWake {

        @DisplayName("to words that were not meant for it")
        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "hello", "hey there", "hi how are you", "ok fine",
                "aaj ke task dikhao", "show my tasks", "ravi sharma ko dikhao",
                "the cheetah ran fast", "hello everyone", "haan bolo",
                "हैलो", "आज के टास्क दिखाओ",
        })
        void toOrdinaryTalk(String said) {
            assertThat(WakeWord.find(said))
                    .withFailMessage("should have stayed asleep through \"%s\"", said)
                    .isNull();
        }

        @Test
        @DisplayName("to silence")
        void toNothing() {
            assertThat(WakeWord.find("")).isNull();
            assertThat(WakeWord.find("   ")).isNull();
            assertThat(WakeWord.find(null)).isNull();
        }

        @Test
        @DisplayName("to the name buried mid-sentence")
        void toTheNameInPassing() {
            // "I told him about xeta yesterday" is talk, not an instruction.
            assertThat(WakeWord.find("I told him about xeta yesterday")).isNull();
        }

        @Test
        @DisplayName("to a mishearing that collides with an everyday word")
        void toAMishearingThatIsAlsoARealWord() {
            /*
             * "theta" is a plausible thing to hear for "xeta", and it was on
             * the list until this test showed what it costs: "there" is two
             * edits from it, so "hey there" woke the assistant up. Being deaf
             * to one unlikely mishearing is much cheaper than answering people
             * who were talking to someone else.
             */
            assertThat(WakeWord.find("hey theta")).isNull();
            assertThat(WakeWord.find("hey there")).isNull();
        }
    }

    @Nested
    @DisplayName("what it hears after the phrase")
    class TheCommand {

        @Test
        @DisplayName("is everything that followed")
        void carriesTheRest() {
            assertThat(WakeWord.find("hey xeta aaj ke task dikhao").command())
                    .isEqualTo("aaj ke task dikhao");
        }

        @Test
        @DisplayName("survives the comma the recogniser puts in")
        void punctuationIsNotPartOfIt() {
            assertThat(WakeWord.find("Hey XetaX, show my sales pipeline records.").command())
                    .isEqualTo("show my sales pipeline records");
        }

        @Test
        @DisplayName("drops the politeness nobody meant as a command")
        void fillerIsNotACommand() {
            assertThat(WakeWord.find("hey xeta ji ravi ko dikhao").command())
                    .isEqualTo("ravi ko dikhao");
        }

        @Test
        @DisplayName("keeps Hindi as Hindi")
        void worksInDevanagari() {
            assertThat(WakeWord.find("हे ज़ेटा आज के टास्क दिखाओ").command())
                    .isEqualTo("आज के टास्क दिखाओ");
        }

        @Test
        @DisplayName("is blank when only the name was called")
        void nothingAfterTheName() {
            assertThat(WakeWord.find("hey xetax").command()).isEmpty();
        }
    }
}
