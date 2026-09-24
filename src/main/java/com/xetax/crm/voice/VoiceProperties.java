package com.xetax.crm.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the voice assistant reads from configuration.
 *
 * <p>Both providers are bound here rather than injected as loose @Value
 * strings so that swapping one is a change in application.yaml, not a hunt
 * through the code.
 */
@ConfigurationProperties(prefix = "voice")
public class VoiceProperties {

    private boolean enabled = true;
    private int sessionIdleSeconds = 900;
    /** Language to answer in when what was said is too short to tell. */
    private String defaultLanguage = "hi";
    private final Stt stt = new Stt();
    private final Tts tts = new Tts();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getSessionIdleSeconds() { return sessionIdleSeconds; }
    public void setSessionIdleSeconds(int sessionIdleSeconds) { this.sessionIdleSeconds = sessionIdleSeconds; }

    public String getDefaultLanguage() { return defaultLanguage; }
    public void setDefaultLanguage(String defaultLanguage) { this.defaultLanguage = defaultLanguage; }

    public Stt getStt() { return stt; }
    public Tts getTts() { return tts; }

    /** Speech in. */
    public static class Stt {
        private String apiKey = "";
        private String baseUrl = "https://api.groq.com/openai/v1";
        private String model = "whisper-large-v3-turbo";
        private int maxSeconds = 30;
        private long maxBytes = 6_291_456L;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxSeconds() { return maxSeconds; }
        public void setMaxSeconds(int maxSeconds) { this.maxSeconds = maxSeconds; }
        public long getMaxBytes() { return maxBytes; }
        public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
    }

    /** Speech out. */
    public static class Tts {
        private String provider = "azure";
        private String apiKey = "";
        private String region = "centralindia";
        private String outputFormat = "audio-24khz-48kbitrate-mono-mp3";
        private String rate = "+8%";
        private String pitch = "+0Hz";
        private int maxChars = 600;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getOutputFormat() { return outputFormat; }
        public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
        public String getRate() { return rate; }
        public void setRate(String rate) { this.rate = rate; }
        public String getPitch() { return pitch; }
        public void setPitch(String pitch) { this.pitch = pitch; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
    }
}
