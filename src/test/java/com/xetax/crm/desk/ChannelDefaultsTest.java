package com.xetax.crm.desk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Every AI reply on WhatsApp is paid from 1 October 2026, so a fresh setup hands off sooner. */
class ChannelDefaultsTest {

    @Test
    void aNewAgentHandsOffAfterTenAiReplies() {
        AgentChannelConfig config = ChannelConfigService.defaults(1L, "owner-1");
        assertEquals(10, config.getMaxAiTurns());
        assertEquals(10, ChannelConfigService.DEFAULT_MAX_AI_TURNS);
    }

    @Test
    void aNewAgentIsNotOnWhatsAppUntilSwitchedOn() {
        assertFalse(ChannelConfigService.defaults(1L, "owner-1").isWhatsappEnabled());
    }
}
