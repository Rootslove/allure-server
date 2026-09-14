package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.iopump.qa.allure.repo.JpaReportRepository;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other half of the "the model did not deliver" case: no OpenCode at the configured address at
 * all. It needs its own context because the address is a startup property - the sibling
 * {@link AiAnalysisIntegrationTest} covers the case where OpenCode answers but its answers are
 * unusable. Both must end the same way: the job fails and no second report is published.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-analysis-down-test-db;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.security.require-api-auth=false",
    "basic.auth.enable=false",
    "gg.jte.development-mode=false",
    "gg.jte.use-precompiled-templates=true",
    "allure.results-dir=build/ai-analysis-down-it/results/",
    "allure.reports.dir=build/ai-analysis-down-it/reports/",
    "allure-ai.cache-dir=build/ai-analysis-down-it/cache",
    "allure-ai.parallel=1",
    "allure-ai.timeout-seconds=10",
    "allure-ai.sweep-cron=-",
    // Nothing listens here: the discovery probe of the client fails outright.
    "allure-ai.opencode-url=http://127.0.0.1:1"
})
class AiAnalysisOpenCodeDownTest {

    private static final Path WORK_DIR = Paths.get("build", "ai-analysis-down-it");
    private static final Path RESULTS_DIR = WORK_DIR.resolve("results");
    private static final String API_REPORT = "/api/report";
    private static final String REPORT_PATH_HEAD = "ai";
    private static final String REPORT_PATH_TAIL = "down";
    private static final long AWAIT_TIMEOUT_MS = 60_000L;

    static {
        FileUtils.deleteQuietly(WORK_DIR.toFile());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JpaReportRepository reportRepository;

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Test
    @DisplayName("should fail the job and publish nothing when OpenCode is unreachable")
    void failsTheJobWhenOpenCodeIsUnreachable() throws Exception {
        // GIVEN - a report generated with the analysis, while no OpenCode is running
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String body = """
            {"reportSpec":{"path":["%s","%s"],"executorInfo":{"buildName":"nightly"}},
             "results":["%s"],"deleteResults":true,"aiAnalysis":true}
            """.formatted(REPORT_PATH_HEAD, REPORT_PATH_TAIL, resultUuid);
        final String response = mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        final String pendingUuid = objectMapper.readTree(response).path("uuid").asText();

        // WHEN - the model is asked for an analysis it cannot reach
        mockMvc.perform(post(API_REPORT + "/" + pendingUuid + "/ai")).andExpect(status().isAccepted());

        // THEN - the job fails and the pending report stays the only one of its path
        assertThat(awaitFinished(pendingUuid))
            .as("job status when OpenCode does not answer at all")
            .isEqualTo(AiJobStatus.ERROR);
        assertThat(reportRepository.findByPath(REPORT_PATH_HEAD + "/" + REPORT_PATH_TAIL))
            .as("reports of this path after an unreachable model")
            .hasSize(1);
    }

    private AiJobStatus awaitFinished(String uuid) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        AiJobStatus status = aiAnalysisService.status(uuid).getStatus();
        while (System.currentTimeMillis() < deadline && inFlight(status)) {
            Thread.sleep(50L);
            status = aiAnalysisService.status(uuid).getStatus();
        }
        return status;
    }

    private static boolean inFlight(AiJobStatus status) {
        return status == AiJobStatus.PENDING || status == AiJobStatus.QUEUED || status == AiJobStatus.RUNNING;
    }
}
