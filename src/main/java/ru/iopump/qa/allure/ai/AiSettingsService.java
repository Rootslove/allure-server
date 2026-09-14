package ru.iopump.qa.allure.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.iopump.qa.allure.service.SystemSettingsService;

/**
 * Effective settings of the AI analysis: the admin panel wins over the configuration.
 * <p>
 * Each setting is resolved on its own - an admin who only wants another model leaves the rest of
 * the card empty and keeps the {@code allure-ai.*} values for everything else. The row is read
 * through {@link SystemSettingsService#current()}, the lock-free in-memory snapshot, so resolving
 * the settings costs no database round-trip and can be done on the hot path.
 * <p>
 * {@link AiProperties#cacheDir()} and {@link AiProperties#sweepCron()} are deliberately absent:
 * moving the result copies while a job is running has nothing to do with settings, and a cron of
 * a {@code @Scheduled} method is fixed when the context starts.
 */
@Service
@RequiredArgsConstructor
public class AiSettingsService {

    private final AiProperties properties;
    private final SystemSettingsService systemSettingsService;

    /**
     * One consistent set of values with the origin of each. Callers that need several settings must
     * take one {@link Effective} and use it to the end: a settings change in the middle of a job
     * must not make the job run half with the old values and half with the new ones.
     */
    public Effective effective() {
        final SystemSettingsService.Snapshot snapshot = systemSettingsService.current();
        return new Effective(
            resolve(snapshot.aiEnabled(), properties.enabled()),
            resolve(snapshot.aiOpencodeUrl(), properties.opencodeUrl()),
            resolve(snapshot.aiProvider(), properties.provider()),
            resolve(snapshot.aiModel(), properties.model()),
            resolve(snapshot.aiAgent(), properties.agent()),
            resolve(snapshot.aiParallel(), properties.parallel()),
            resolve(snapshot.aiTimeoutSeconds(), properties.timeoutSeconds()),
            resolve(snapshot.aiAuto(), properties.auto())
        );
    }

    private static <T> Value<T> resolve(T fromSettings, T fromConfiguration) {
        return fromSettings == null
            ? new Value<>(fromConfiguration, Source.CONFIGURATION)
            : new Value<>(fromSettings, Source.SETTINGS);
    }

    /** Where an effective value comes from. */
    public enum Source {
        /** From {@code allure-ai.*}: yaml, environment or a command-line argument. */
        CONFIGURATION,
        /** From the settings row, i.e. entered in the admin panel. */
        SETTINGS
    }

    /**
     * An effective value together with its origin.
     *
     * @param value  the value in force right now
     * @param source where it comes from
     */
    public record Value<T>(T value, Source source) {

        public boolean fromSettings() {
            return source == Source.SETTINGS;
        }
    }

    /** The eight settings of the AI analysis as they are in force at one moment. */
    public record Effective(Value<Boolean> enabled,
                            Value<String> opencodeUrl,
                            Value<String> provider,
                            Value<String> model,
                            Value<String> agent,
                            Value<Integer> parallel,
                            Value<Long> timeoutSeconds,
                            Value<Boolean> auto) {
    }
}
