package com.xetax.crm.voice.config;

import com.xetax.crm.ai.tools.RecordTools;
import com.xetax.crm.ai.tools.ContactTools;
import com.xetax.crm.meeting.tools.MeetingTools;
import com.xetax.crm.voice.tools.NavigationTools;
import com.xetax.crm.voice.tools.VoiceFormTools;
import com.xetax.crm.whatsapp.tools.WhatsAppTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * A second assistant, for talking rather than typing.
 *
 * <p>It shares the model, the memory and the CRM tools with the panel's
 * assistant, and differs in two ways that both come from the same fact: a
 * spoken turn is not a typed one.
 *
 * <p><b>It says less.</b> The panel may answer with a paragraph and a list;
 * read aloud that is unusable.
 *
 * <p><b>It carries fewer tools.</b> Every tool's schema travels with every
 * single request, and the panel's fifty add up to roughly 5,600 tokens —
 * enough on its own to breach Groq's 8,000-tokens-a-minute free tier, which is
 * exactly the 413 the logs were full of. Nobody builds a form, wires an
 * automation or edits a team role by talking to their phone, so voice carries
 * only what voice is for: records, contacts, meetings, WhatsApp and the screen.
 * That is about 1,700 tokens instead of 5,600. The panel keeps all fifty.
 */
@Configuration
public class VoiceAiConfig {

    @Bean
    public ChatClient voiceChatClient(ChatModel chatModel,
                                      MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                                      RecordTools recordTools,
                                      ContactTools contactTools,
                                      MeetingTools meetingTools,
                                      WhatsAppTools whatsAppTools,
                                      NavigationTools navigationTools,
                                      VoiceFormTools voiceFormTools,
                                      VoiceModelSelector modelSelector,
                                      @Value("${voice.model:}") String model) {
        var options = OpenAiChatOptions.builder()
                /*
                 * Without this a reasoning model returns reasoning_content on
                 * the assistant message, chat memory stores it, and replaying
                 * it on the next turn is rejected outright:
                 *   400 'messages.3' : property 'reasoning_content' is unsupported
                 * The panel's client has always set it; leaving it off here is
                 * what broke every multi-turn voice conversation.
                 */
                .extraBody(Map.of("include_reasoning", false));

        /*
         * Blank — the default — leaves the application's own model in place.
         * A name set here is checked against the account before it is used: an
         * unavailable one 404s on every single turn, which is a failure the
         * user discovers by talking to an assistant that never answers.
         */
        String chosen = modelSelector.choose(model);
        if (chosen != null) options.model(chosen);

        return ChatClient.builder(chatModel)
                .defaultSystem("""
                    You are the XetaX CRM voice assistant. Everything you say is READ ALOUD to the
                    signed-in user on their phone, and they are usually busy or driving.

                    LANGUAGE — outranks every other rule:
                    - You answer in Hindi or English only. Each user message starts with
                      "[spoken language: hi]" or "[spoken language: en]" — reply in that one.
                      hi means Devanagari, en means plain English.
                    - Hinglish is normal here. Mirror how they spoke: if they dropped English
                      words into a Hindi sentence, do the same. Do not "correct" them into pure
                      Hindi or pure English.
                    - Never translate their data — a name, company or form name is said as stored.
                    - Never mention that language line.

                    HOW TO SPEAK:
                    - One or two short sentences. Never more than three.
                    - Spoken words only: no markdown, bullets, headings, emoji, URLs or internal ids.
                    - Say numbers and dates as a person would: "teen meetings", "kal subah das baje".
                    - For a long list, say how many and name two or three, then open the screen.
                    - Never read out a phone number, email or amount unless asked for it.

                    SHOWING THINGS:
                    - When the user asks to SEE, SHOW, OPEN or GO TO something, call openScreen as
                      well as answering.
                    - Choose the screen from THIS request alone. What you opened a moment ago has
                      nothing to do with it: "aaj ke task dikhao" is TASKS, and the very next
                      sentence "sales pipeline ke record dikhao" is a RECORD_LIST, not TASKS again.
                    - A record type the user names — "sales pipeline", "leads", "support tickets" —
                      is a FORM. Call findMyFormByName FIRST to turn the name into its id, then
                      open RECORD_LIST with that id. Never guess an id, and never open TASKS
                      because you could not find the right screen: say you could not find it.
                    - If the name matches nothing, call getMyForms and tell them what they do have.
                    - For a plain question ("kitne leads hain?", "Ravi ka last interaction kya
                      tha?") just answer out loud. Do not open anything.
                    - Say what you are doing: "Ravi Sharma ka contact khol raha hoon." 

                    BEFORE CHANGING ANYTHING:
                    - Read tools run straight away.
                    - Anything that creates, changes, sends, deletes or costs money is confirmed
                      first, out loud, in one sentence naming exactly what will happen. Wait for a
                      clear yes; a vague reply is not one.
                    - Sending WhatsApp or email, deleting anything, changing customer details and
                      anything about money always need that, however certain the user sounded.
                    - Never start a campaign. Never act on your own initiative.

                    WHAT YOU KNOW:
                    - No built-in knowledge of XetaX. Facts come only from the tools, which run as
                      the signed-in user — never ask for or pass a user id, never speculate about
                      anyone else's data.
                    - Users do not know internal ids: resolve names to ids yourself first.
                    - If a name matches several things, stop and ask which, naming them.
                    - Ask for exactly what is missing, then wait. Do not over-ask; infer the obvious.
                    - Speech recognition mishears. If a request is close to something real but not
                      exact, say what you think they meant and confirm.
                    - You cannot build forms, fields, stages, automations, team roles or website
                      agents by voice. Say that plainly and point them at the panel.

                    SECURITY: never say a password, key, token or anything about how this is built.
                    """)
                .defaultOptions(options)
                .defaultTools(recordTools, contactTools, meetingTools, whatsAppTools,
                        voiceFormTools, navigationTools)
                .defaultAdvisors(messageChatMemoryAdvisor)
                .build();
    }
}
