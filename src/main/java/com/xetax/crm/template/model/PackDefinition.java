package com.xetax.crm.template.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A "vertical pack": everything a business needs on day one, as plain data.
 * Built-in packs live in {@code classpath:packs/*.json}; tenants can also
 * export any of their forms into a pack (see {@code vertical_packs}).
 *
 * Every section is optional except fields/stages. Stage references inside a
 * pack always use the stage {@code code} (never ids), field references use
 * the {@code fieldKey} — so a pack is portable across workspaces.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PackDefinition {

    private String key;
    private String name;
    private String icon;
    private String color;
    private String tagline;
    private String description;
    /** Free text industry label, e.g. "Real estate", "Healthcare". */
    private String industry;
    private List<String> tags = new ArrayList<>();
    /** Preferred customer language for messages: "en", "hi", "hinglish"… */
    private String language;

    private List<Field> fields = new ArrayList<>();
    private List<Stage> stages = new ArrayList<>();
    private List<Automation> automations = new ArrayList<>();
    /** WhatsApp templates shipped as local drafts, submitted to Meta once connected. */
    private List<WaTemplate> whatsappTemplates = new ArrayList<>();
    private AgentSpec agent;
    private PlaybookSpec playbook;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Field {
        private String label;
        private String fieldKey;
        private String fieldType;
        private boolean required;
        private String optionsJson;
        private String placeholder;
        private Integer displayOrder;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stage {
        private String name;
        private String code;
        private int sequence;
        private boolean isDefault;
        private boolean isFinal;
        private String color;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Automation {
        private String name;
        private String trigger;
        private String triggerStageCode;
        private String actionType;
        private String actionFieldKey;
        private String actionValue;
        /** CHANGE_STAGE: target stage by code (resolved to an id on apply). */
        private String actionStageCode;
        private String emailSubject;
        private String emailMessage;
        private String note;
        private String channel;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WaTemplate {
        /** lowercase letters, digits, underscores — Meta naming rule. */
        private String name;
        /** MARKETING | UTILITY */
        private String category = "UTILITY";
        private String language = "en";
        private String headerText;
        private String bodyText;
        private String footerText;
        private List<String> exampleParams = new ArrayList<>();
        /** Human label shown in the panel: "Site visit reminder". */
        private String purpose;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentSpec {
        private String name;
        private String persona;
        private String welcomeMessage;
        private String themeColor;
        private String handoffKeywords;
        /** SUGGEST | AUTO */
        private String pipelineMode = "SUGGEST";
        private boolean captureFields = true;
        private List<StageHint> stageHints = new ArrayList<>();
        /** Knowledge text indexed for the agent (FAQ etc.). */
        private List<KnowledgeText> knowledge = new ArrayList<>();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StageHint {
        private String stageCode;
        private String hint;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KnowledgeText {
        private String name;
        private String text;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlaybookSpec {
        private String goal;
        private List<String> qualificationKeys = new ArrayList<>();
        private Integer quietStart;
        private Integer quietEnd;
        private Integer maxFollowUps;
        private List<Rule> rules = new ArrayList<>();
    }

    /** Same shape as {@code PlaybookRule} but with stage codes instead of ids. */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rule {
        private String name;
        private String trigger;
        private Integer afterMinutes;
        private List<String> stageCodes = new ArrayList<>();
        private String interest;
        private Integer maxRepeats;
        private Integer repeatEveryMinutes;
        private String action;
        private String message;
        private boolean aiCompose;
        private String templateName;
        private List<String> templateParams = new ArrayList<>();
        private String targetStageCode;
        private String taskTitle;
        private Integer taskDueHours;
        private boolean active = true;
        private List<Condition> conditions = new ArrayList<>();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Condition {
        private String fieldKey;
        private String op;
        private String value;
    }
}
