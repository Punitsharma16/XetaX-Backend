package com.xetax.crm.voice.tools;

import com.xetax.crm.ai.tools.FormTools;
import com.xetax.crm.data_manager.dto.FormResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The two form lookups a spoken turn cannot do without.
 *
 * <p>Every record tool takes a numeric formId, and a person says "sales
 * pipeline", not 47. Leaving these out to save tokens did not make the
 * assistant cheaper — it made it wrong: with no way to turn a name into an id
 * it could not open anything record-shaped, so it fell back to whatever screen
 * it had opened last, which is why asking for the sales pipeline kept landing
 * on Tasks.
 *
 * <p>This is a delegation, not a second implementation: the permission checks,
 * ownership rules and error handling all stay in {@link FormTools}. Only the
 * two read lookups are exposed — nothing here can create or change a form,
 * which is panel work and not something anyone does by talking.
 */
@Component
public class VoiceFormTools {

    private final FormTools formTools;

    public VoiceFormTools(FormTools formTools) {
        this.formTools = formTools;
    }

    @Tool(description = """
            READ-ONLY. Find the user's form (record type) by the name they said.
            Match is partial and case-insensitive, so "sales pipeline" works.
            Call this FIRST whenever the user names a record type — every record
            tool needs the numeric formId this returns, and so does opening the
            RECORD_LIST screen. If matchCount is more than 1, ask which one.
            """)
    public Map<String, Object> findMyFormByName(
            @ToolParam(description = "Form name or part of it, as the user said it") String name) {
        return formTools.findMyFormByName(name);
    }

    @Tool(description = """
            READ-ONLY. List every form (record type) the user has. Use it when
            they ask what record types exist, or when a name they said matches
            nothing and you need to tell them what they do have.
            """)
    public List<FormResponse> getMyForms() {
        return formTools.getMyForms();
    }
}
