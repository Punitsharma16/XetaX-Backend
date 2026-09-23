package com.xetax.crm.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Production ran with META_LEADS_CONFIG_ID set to the literal text
 * "<naya configuration id>". Because that is not blank, every configured-check
 * said Facebook Ads was ready, the panel drew a live Connect button, and the
 * placeholder was handed to Facebook as the config id.
 */
class ConfigValuesTest {

    @Test
    @DisplayName("a placeholder left in the deployment is not a setting")
    void placeholdersAreNotSet() {
        assertThat(ConfigValues.isSet("<naya configuration id>")).isFalse();
        assertThat(ConfigValues.isSet("<your app id here>")).isFalse();
        assertThat(ConfigValues.isSet("  <TODO>  ")).isFalse();
        assertThat(ConfigValues.orEmpty("<naya configuration id>")).isEmpty();
    }

    @Test
    @DisplayName("nothing is still nothing")
    void blanksAreNotSet() {
        assertThat(ConfigValues.isSet(null)).isFalse();
        assertThat(ConfigValues.isSet("")).isFalse();
        assertThat(ConfigValues.isSet("   ")).isFalse();
        assertThat(ConfigValues.orEmpty(null)).isEmpty();
    }

    @Test
    @DisplayName("a real value is passed through, trimmed")
    void realValuesSurvive() {
        assertThat(ConfigValues.isSet("2584936938590554")).isTrue();
        assertThat(ConfigValues.orEmpty("  2584936938590554 ")).isEqualTo("2584936938590554");
        assertThat(ConfigValues.isSet("v23.0")).isTrue();
    }

    @Test
    @DisplayName("an angle bracket inside a real value does not make it a placeholder")
    void onlyWholeValuesCount() {
        // Both ends have to be brackets; a stray one is just a character.
        assertThat(ConfigValues.isSet("<abc")).isTrue();
        assertThat(ConfigValues.isSet("abc>")).isTrue();
        assertThat(ConfigValues.isSet("a<b>c")).isTrue();
    }
}
