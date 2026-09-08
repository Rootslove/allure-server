package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a server restart does to jobs that were in flight when the process died. Both {@code running}
 * and {@code queued} live only in memory - the worker thread and its queue go with the process - so
 * the file left on disk is all there is, and it must not be believed as it stands.
 * <p>
 * The two jobs are written into the cache directory <em>before</em> the context boots, which is the
 * only way to be in the position a real restart leaves behind. Resubmitting them would be the
 * dangerous fix: a server that dies during the analysis would then fire the model on every start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-analysis-restart-test-db;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.security.require-api-auth=false",
    "basic.auth.enable=false",
    "gg.jte.development-mode=false",
    "gg.jte.use-precompiled-templates=true",
    "allure.results-dir=build/ai-analysis-restart-it/results/",
    "allure.reports.dir=build/ai-analysis-restart-it/reports/",
    "allure-ai.cache-dir=build/ai-analysis-restart-it/cache",
    "allure-ai.sweep-cron=-"
})
class AiAnalysisRestartTest {

    private static final Path WORK_DIR = Paths.get("build", "ai-analysis-restart-it");
    private static final Path CACHE_DIR = WORK_DIR.resolve("cache");
    private static final String JOB_FILE = "ai-job.json";
    private static final String RESTART_ERROR = "interrupted by a server restart";

    static {
        FileUtils.deleteQuietly(WORK_DIR.toFile());
    }

    /** The state the previous process left on disk; written before the context boots. */
    private static final String RUNNING_UUID = writeJob("running");
    private static final String QUEUED_UUID = writeJob("queued");

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Test
    @DisplayName("should fail a job that was running when the server died and never resubmit it")
    void failsTheRunningJobWithoutResubmitting() throws IOException {
        // GIVEN - a job left in 'running' by the previous process
        // WHEN - the context has started, so the service has already restored its jobs
        final JsonNode job = readJob(RUNNING_UUID);

        // THEN - it is closed as failed on disk, with the reason an operator can act on
        assertThat(job.path("status").asText())
            .as("status of a job that was running at the restart")
            .isEqualTo("error");
        assertThat(job.path("error").asText())
            .as("reason recorded for the interrupted job")
            .isEqualTo(RESTART_ERROR);

        // AND - the worker never picked it up: only a run of the model sets startedAt
        assertThat(aiAnalysisService.status(RUNNING_UUID).getStartedAt())
            .as("start time of a job that was not put back into the queue")
            .isNull();
    }

    @Test
    @DisplayName("should fail a job that was still queued when the server died and never resubmit it")
    void failsTheQueuedJobWithoutResubmitting() throws IOException {
        // GIVEN - a job left in 'queued' by the previous process
        // WHEN - the context has started
        final JsonNode job = readJob(QUEUED_UUID);

        // THEN - the same verdict: the queue itself did not survive the restart
        assertThat(job.path("status").asText())
            .as("status of a job that was queued at the restart")
            .isEqualTo("error");
        assertThat(job.path("error").asText())
            .as("reason recorded for the interrupted job")
            .isEqualTo(RESTART_ERROR);
        assertThat(aiAnalysisService.status(QUEUED_UUID).getStartedAt())
            .as("start time of a job that was not put back into the queue")
            .isNull();
    }

    @Test
    @DisplayName("should serve the restored jobs as failed to the api and the reports grid")
    void servesTheRestoredStatus() {
        // GIVEN - the two restored jobs
        // WHEN - their status is asked the way GET /api/report/{uuid}/ai and the grid ask for it
        // THEN - the served state agrees with the files, so no button offers a dead job as running
        assertThat(aiAnalysisService.status(RUNNING_UUID).getStatus())
            .as("status served for the interrupted running job")
            .isEqualTo(AiJobStatus.ERROR);
        assertThat(aiAnalysisService.statuses())
            .as("statuses of every known job after the restore")
            .containsEntry(RUNNING_UUID, AiJobStatus.ERROR)
            .containsEntry(QUEUED_UUID, AiJobStatus.ERROR);
    }

    //// PRIVATE ////

    private JsonNode readJob(String uuid) throws IOException {
        return objectMapper.readTree(CACHE_DIR.resolve(uuid).resolve(JOB_FILE).toFile());
    }

    /** Lays out {@code <cache-dir>/<uuid>/ai-job.json} with the given status and returns the uuid. */
    private static String writeJob(String status) {
        final String uuid = UUID.randomUUID().toString();
        try {
            final Path dir = CACHE_DIR.resolve(uuid);
            Files.createDirectories(dir.resolve("results"));
            Files.writeString(dir.resolve(JOB_FILE), """
                {"status":"%s","reportPath":"ai/restart","clusters":2,"createdAt":"2026-09-08T03:00:00Z"}
                """.formatted(status), StandardCharsets.UTF_8);
            return uuid;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
