package ru.iopump.qa.allure.ai;

import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

/**
 * Settings of the AI-analysis add-on, bound from {@code allure-ai.*} (relaxed binding applies, so
 * {@code ALLURE_AI_OPENCODE_URL} works as an environment variable).
 * <p>
 * No secret lives here: the credentials of the model gateway belong to the OpenCode instance this
 * server only knows the URL of, so the whole object is safe to log at startup.
 */
@ConfigurationProperties(prefix = "allure-ai")
@Getter
@Accessors(fluent = true)
@Slf4j
@ToString
public class AiProperties {

    /** Master switch. When false, {@code aiAnalysis=true} degrades to a plain generation. */
    private final boolean enabled;

    /** Base URL of a running {@code opencode serve} instance reachable from this server. */
    private final String opencodeUrl;

    /** OpenCode provider id (the LiteLLM gateway in production, {@code lmstudio} locally). */
    private final String provider;

    /** Model id inside the provider. */
    private final String model;

    /** OpenCode agent; it must be declared without tools, see the allure-ai documentation. */
    private final String agent;

    /** Clusters analysed in parallel inside one job. Jobs themselves never overlap. */
    private final int parallel;

    /** Timeout of a single model answer. */
    private final long timeoutSeconds;

    /** Start the worker automatically right after a report is generated with {@code aiAnalysis}. */
    private final boolean auto;

    /** Where result copies and {@code ai-job.json} live; a relative path resolves against the work dir. */
    private final String cacheDir;

    /** Cron of the housekeeping sweep for copies whose report is gone. Daily by default. */
    private final String sweepCron;

    @ConstructorBinding
    public AiProperties(@Nullable Boolean enabled,
                        @Nullable String opencodeUrl,
                        @Nullable String provider,
                        @Nullable String model,
                        @Nullable String agent,
                        @Nullable Integer parallel,
                        @Nullable Long timeoutSeconds,
                        @Nullable Boolean auto,
                        @Nullable String cacheDir,
                        @Nullable String sweepCron) {
        this.enabled = defaultIfNull(enabled, true);
        this.opencodeUrl = defaultIfNull(opencodeUrl, "http://127.0.0.1:4096");
        this.provider = defaultIfNull(provider, "litellm");
        this.model = defaultIfNull(model, "qwen3.8");
        this.agent = defaultIfNull(agent, "allure-ai");
        this.parallel = defaultIfNull(parallel, 2);
        this.timeoutSeconds = defaultIfNull(timeoutSeconds, 300L);
        this.auto = defaultIfNull(auto, false);
        this.cacheDir = defaultIfNull(cacheDir, "allure/allure-ai");
        this.sweepCron = defaultIfNull(sweepCron, "0 30 3 * * *");
    }

    @PostConstruct
    void init() {
        if (log.isInfoEnabled())
            log.info("[ALLURE SERVER CONFIGURATION] AI analysis parameters: {}", this);
    }
}
