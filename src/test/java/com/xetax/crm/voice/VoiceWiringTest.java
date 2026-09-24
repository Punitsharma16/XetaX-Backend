package com.xetax.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.xetax.crm.ai.services.AiChatService;
import com.xetax.crm.voice.assistant.VoiceAssistant;
import com.xetax.crm.voice.socket.VoiceSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Two assistants, one application.
 *
 * <p>Adding the voice assistant put a second {@link ChatClient} in the context
 * beside the panel's. That is the one way this feature can break something
 * that already worked: an injection point asking for a bare ChatClient now has
 * two to choose from, and until @Primary was added the choice was being made
 * by matching a parameter name against a bean name — which holds until someone
 * renames a parameter.
 */
@SpringBootTest
class VoiceWiringTest {

    @Autowired
    private ChatClient anyChatClient;

    @Autowired
    @Qualifier("chatClient")
    private ChatClient panelChatClient;

    @Autowired
    @Qualifier("voiceChatClient")
    private ChatClient voiceChatClient;

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private VoiceAssistant voiceAssistant;

    @Autowired
    private VoiceSocketHandler voiceSocketHandler;

    @Test
    @DisplayName("both assistants exist, and they are not the same one")
    void bothClientsExist() {
        assertThat(panelChatClient).isNotNull();
        assertThat(voiceChatClient).isNotNull();
        assertThat(voiceChatClient)
                .withFailMessage("the voice client must be its own — sharing one would give the "
                        + "panel the voice prompt and the cut-down toolset")
                .isNotSameAs(panelChatClient);
    }

    @Test
    @DisplayName("an unqualified ChatClient is still the panel's")
    void unqualifiedResolvesToThePanel() {
        // This is what every pre-existing injection point asks for.
        assertThat(anyChatClient).isSameAs(panelChatClient);
    }

    @Test
    @DisplayName("the panel's assistant still starts")
    void thePanelAssistantStillWorks() {
        assertThat(aiChatService).isNotNull();
    }

    @Test
    @DisplayName("the voice side is wired end to end")
    void theVoiceSideIsWired() {
        assertThat(voiceAssistant).isNotNull();
        assertThat(voiceSocketHandler).isNotNull();
    }
}
