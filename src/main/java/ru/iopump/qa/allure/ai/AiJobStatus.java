package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Lifecycle of the model analysis attached to one generated report.
 * <ul>
 *   <li>{@code none} — no result copy for this report, nothing to analyse;</li>
 *   <li>{@code pending} — copy is on disk, the model was never asked;</li>
 *   <li>{@code queued} / {@code running} — accepted by the single-threaded worker;</li>
 *   <li>{@code done} — every cluster got an answer (or there were no clusters at all);</li>
 *   <li>{@code partial} — some clusters are still without an answer, a retry only redoes those;</li>
 *   <li>{@code error} — no answer at all, the report was not regenerated.</li>
 * </ul>
 * Serialised lower-case so {@code ai-job.json} matches the vocabulary of {@code ai-analysis.json}.
 */
public enum AiJobStatus {
    NONE, PENDING, QUEUED, RUNNING, DONE, PARTIAL, ERROR;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static AiJobStatus of(String value) {
        return value == null || value.isBlank() ? NONE : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** States a new run may be started from; the UI offers the button exactly for these. */
    public boolean restartable() {
        return this == PENDING || this == PARTIAL || this == ERROR;
    }
}
