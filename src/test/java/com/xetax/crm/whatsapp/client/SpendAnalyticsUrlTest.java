package com.xetax.crm.whatsapp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * The URL Meta actually receives when we ask what WhatsApp cost.
 *
 * <p>Both spend calls were failing in production with "The parameter
 * dimensions must be an array". The dimension was written pre-encoded as
 * %22PRICING_CATEGORY%22, and RestClient encodes what it is handed — so the
 * percent itself became %25 and Meta read six literal characters where a
 * quoted string should have been. The panel showed no WhatsApp spend at all.
 *
 * <p>These assertions are made against the URL after the same encoder
 * RestClient uses, because the raw string looked perfectly correct.
 */
class SpendAnalyticsUrlTest {

    private final MetaWhatsAppClient client =
            new MetaWhatsAppClient(properties(), new ObjectMapper());

    /** Exactly what RestClient does to a URL string before sending it. */
    private static String asSent(String url) {
        return new DefaultUriBuilderFactory().uriString(url).build().toString();
    }

    private static MetaWhatsAppProperties properties() {
        var props = new MetaWhatsAppProperties();
        props.setGraphApiVersion("v21.0");
        return props;
    }

    @Test
    @DisplayName("the pricing dimension reaches Meta as a quoted string")
    void pricingDimensionIsQuoted() {
        String sent = asSent(client.pricingAnalyticsPath("1234567890", 1_756_000_000L, 1_758_000_000L));

        assertThat(sent)
                .withFailMessage("Meta must see [\"PRICING_CATEGORY\"], not the characters %%22")
                .contains("dimensions(%5B%22PRICING_CATEGORY%22%5D)");
    }

    @Test
    @DisplayName("the conversation fallback carries the same shape")
    void conversationFallbackIsQuoted() {
        String sent = asSent(client.conversationAnalyticsPath("1234567890", 1L, 2L));

        assertThat(sent).contains("dimensions(%5B%22CONVERSATION_CATEGORY%22%5D)");
        assertThat(sent).contains("metric_types=%5B%22COST%22,%22CONVERSATION%22%5D");
    }

    @Test
    @DisplayName("nothing is encoded twice")
    void nothingIsDoubleEncoded() {
        // %2522 is what a hand-written %22 turns into, and it is the whole bug.
        assertThat(asSent(client.pricingAnalyticsPath("1", 1L, 2L))).doesNotContain("%2522");
        assertThat(asSent(client.conversationAnalyticsPath("1", 1L, 2L))).doesNotContain("%2522");
    }

    @Test
    @DisplayName("the window and the WABA still travel intact")
    void theRequestStillAsksTheRightThing() {
        String sent = asSent(client.pricingAnalyticsPath("1234567890", 1_756_000_000L, 1_758_000_000L));

        assertThat(sent).contains("/1234567890");
        assertThat(sent).contains("pricing_analytics.start(1756000000).end(1758000000)");
        assertThat(sent).contains("granularity(MONTHLY)");
    }
}
