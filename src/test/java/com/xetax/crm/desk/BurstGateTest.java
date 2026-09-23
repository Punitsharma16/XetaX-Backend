package com.xetax.crm.desk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One burst, one answer.
 *
 * <p>A customer writing "hi", "I need a quote", "by tomorrow" used to get three
 * replies — three AI messages off the quota and, from 1 October 2026, three
 * billable service messages for what is one question.
 */
class BurstGateTest {

    private final BurstGate gate = new BurstGate();

    @Test
    @DisplayName("a single message is answered")
    void oneMessageAnswers() {
        long token = gate.begin(1L);

        assertThat(gate.shouldAnswer(1L, token)).isTrue();
    }

    @Test
    @DisplayName("three lines in a burst produce one answer — the last")
    void aBurstAnswersOnce() {
        long first = gate.begin(1L);
        long second = gate.begin(1L);
        long third = gate.begin(1L);

        assertThat(gate.shouldAnswer(1L, first)).as("overtaken").isFalse();
        assertThat(gate.shouldAnswer(1L, second)).as("overtaken").isFalse();
        assertThat(gate.shouldAnswer(1L, third)).as("the last line answers").isTrue();
    }

    @Test
    @DisplayName("the winner answers once and never again")
    void theWinnerCannotAnswerTwice() {
        long token = gate.begin(1L);

        assertThat(gate.shouldAnswer(1L, token)).isTrue();
        assertThat(gate.shouldAnswer(1L, token))
                .as("a retry or a double schedule must not send a second reply").isFalse();
    }

    @Test
    @DisplayName("two customers do not stand each other down")
    void sessionsAreIndependent() {
        long a = gate.begin(1L);
        long b = gate.begin(2L);

        assertThat(gate.shouldAnswer(1L, a)).isTrue();
        assertThat(gate.shouldAnswer(2L, b)).isTrue();
    }

    @Test
    @DisplayName("a later burst in the same chat answers again")
    void aSecondBurstLaterAnswersAgain() {
        gate.shouldAnswer(1L, gate.begin(1L));

        long later = gate.begin(1L);
        assertThat(gate.shouldAnswer(1L, later)).isTrue();
    }

    @Test
    @DisplayName("nothing is left behind once a burst has been answered")
    void leavesNothingBehind() {
        gate.shouldAnswer(1L, gate.begin(1L));

        assertThat(gate.waiting()).isZero();
    }

    @Test
    @DisplayName("a chat that ends leaves no turn waiting")
    void forgettingASession() {
        gate.begin(1L);
        gate.forget(1L);

        assertThat(gate.waiting()).isZero();
    }

    @Test
    @DisplayName("turns waking at the same instant still yield exactly one answer")
    void exactlyOneWinnerUnderConcurrency() throws Exception {
        // Every line of a fast burst, then all their timers firing together.
        List<Long> tokens = IntStream.range(0, 50).mapToObj(i -> gate.begin(1L)).toList();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> races = tokens.stream()
                    .map(t -> (Callable<Boolean>) () -> gate.shouldAnswer(1L, t))
                    .toList();
            long winners = pool.invokeAll(races).stream().map(BurstGateTest::value)
                    .filter(Boolean::booleanValue).count();

            assertThat(winners).as("one reply, never two").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Boolean value(Future<Boolean> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
