package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.iopump.qa.allure.entity.ReportEntity;
import ru.iopump.qa.allure.entity.UserEntity;
import ru.iopump.qa.allure.repo.JpaReportRepository;
import ru.iopump.qa.allure.repo.UserRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * End-to-end test of the AI analysis over the real generation pipeline: uploaded results are
 * clustered before the report is built, a copy is kept for the model, and the worker publishes a
 * new version of the report once the model has answered.
 * <p>
 * The model is the only thing stubbed ({@link OpenCodeStub} speaks the OpenCode protocol on
 * loopback); the Allure generator, the database and the filesystem layout are the real ones. The
 * report tab plugin is deliberately absent, so every assertion is made on artefacts that do not
 * need it: {@code ai-analysis.json}, {@code categories.json}, {@code environment.properties} and
 * the {@code data/categories.json} the Allure core itself produces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-analysis-test-db;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.security.require-api-auth=false",
    "basic.auth.enable=false",
    "gg.jte.development-mode=false",
    "gg.jte.use-precompiled-templates=true",
    "allure.results-dir=build/ai-analysis-it/results/",
    "allure.reports.dir=build/ai-analysis-it/reports/",
    "allure-ai.cache-dir=build/ai-analysis-it/cache",
    "allure-ai.parallel=1",
    "allure-ai.timeout-seconds=30",
    "allure-ai.auto=false",
    // '-' disables the scheduled sweep: housekeeping must not fire in the middle of a test.
    "allure-ai.sweep-cron=-"
})
class AiAnalysisIntegrationTest {

    private static final Path WORK_DIR = Paths.get("build", "ai-analysis-it");
    private static final Path RESULTS_DIR = WORK_DIR.resolve("results");
    private static final Path REPORTS_DIR = WORK_DIR.resolve("reports");
    private static final Path CACHE_DIR = WORK_DIR.resolve("cache");

    private static final String API_REPORT = "/api/report";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin";
    private static final String AI_SUMMARY = "ai-analysis.json";
    private static final String CATEGORIES = "categories.json";
    private static final String ENVIRONMENT = "environment.properties";
    private static final String AI_CATEGORY_PREFIX = "[AI] ";
    /** Escaped as a .properties key is: the report overview shows it as "AI Analysis". */
    private static final String AI_ENVIRONMENT_KEY = "AI\\ Analysis";
    private static final int EXPECTED_CLUSTERS = 2;
    private static final long AWAIT_TIMEOUT_MS = 120_000L;
    /** Past the hour a copy is protected for, so the sweep is allowed to take it. */
    private static final Duration OLDER_THAN_SWEEP_AGE = Duration.ofHours(2);

    private static final OpenCodeStub STUB;

    static {
        FileUtils.deleteQuietly(WORK_DIR.toFile());
        STUB = OpenCodeStub.start();
    }

    @DynamicPropertySource
    static void openCodeUrl(DynamicPropertyRegistry registry) {
        registry.add("allure-ai.opencode-url", () -> "http://127.0.0.1:" + STUB.port());
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JpaReportRepository reportRepository;

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void answeringModel() {
        STUB.mode(OpenCodeStub.Mode.ANSWERS);
    }

    @Test
    @DisplayName("should cluster the failures into the report and keep the results for the model when aiAnalysis is requested")
    void enrichesTheReportAndKeepsACopy() throws Exception {
        // GIVEN - one nightly run with two distinct failures
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");

        // WHEN - the report is generated with the analysis and the results are asked to be deleted
        final String reportUuid = generate("ai/enrich", resultUuid, true, false, true);

        // THEN - the copy the model will work on carries the offline analysis
        final Path copy = CACHE_DIR.resolve(reportUuid).resolve("results");
        assertThat(readJson(copy.resolve(AI_SUMMARY)).path("status").asText())
            .as("analysis status in the results copy before the model has run")
            .isEqualTo("pending");
        assertThat(readJson(copy.resolve(AI_SUMMARY)).path("clusters"))
            .as("one cluster per distinct failure")
            .hasSize(EXPECTED_CLUSTERS);
        assertThat(Files.readString(copy.resolve(CATEGORIES), StandardCharsets.UTF_8))
            .as("categories written next to the results")
            .contains(AI_CATEGORY_PREFIX);
        assertThat(Files.readString(copy.resolve(ENVIRONMENT), StandardCharsets.UTF_8))
            .as("environment line of the report overview")
            .contains(AI_ENVIRONMENT_KEY);

        // AND - the generated report itself shows the categories, without any plugin
        assertThat(Files.readString(REPORTS_DIR.resolve(reportUuid).resolve("data").resolve(CATEGORIES),
            StandardCharsets.UTF_8))
            .as("categories inside the generated report")
            .contains(AI_CATEGORY_PREFIX);

        // AND - the uploaded results are gone from the intake directory: they were moved, not copied
        assertThat(RESULTS_DIR.resolve(resultUuid))
            .as("uploaded results after a generation that asked to delete them")
            .doesNotExist();
    }

    @Test
    @DisplayName("should leave the uploaded results in place when the request does not ask to delete them")
    void keepsTheUploadedResultsWhenNotAskedToDeleteThem() throws Exception {
        // GIVEN - one nightly run
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");

        // WHEN - the report is generated with deleteResults=false
        final String reportUuid = generate("ai/keep", resultUuid, false, false, true);

        // THEN - both the original and the copy exist
        assertThat(RESULTS_DIR.resolve(resultUuid))
            .as("uploaded results after a generation that keeps them")
            .isDirectory();
        assertThat(CACHE_DIR.resolve(reportUuid).resolve("results").resolve(AI_SUMMARY))
            .as("the analysis copy is taken all the same")
            .isRegularFile();
    }

    @Test
    @DisplayName("should reject aiAnalysis together with singleFile with 400")
    void rejectsAiAnalysisWithSingleFile() throws Exception {
        // GIVEN - a results directory
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");

        // WHEN - both flags are requested at once
        // THEN - 400: a standalone file cannot carry the tab the flag promises
        mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content("""
                {"reportSpec":{"path":["ai","single"]},"results":["%s"],
                 "deleteResults":false,"singleFile":true,"aiAnalysis":true}
                """.formatted(resultUuid)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should reject aiAnalysis with more than one results directory with 400")
    void rejectsAiAnalysisWithSeveralResults() throws Exception {
        // GIVEN - two results directories
        final String first = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");
        final String second = AllureResultsFixture.write(RESULTS_DIR, 2, "beta");

        // WHEN - both are sent in one request with the analysis
        // THEN - 400: the analysis reads one directory as one run, merging is out of scope
        mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content("""
                {"reportSpec":{"path":["ai","two"]},"results":["%s","%s"],
                 "deleteResults":false,"aiAnalysis":true}
                """.formatted(first, second)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should publish a new version of the report when the model answers every cluster")
    void publishesANewReportWhenTheModelAnswers() throws Exception {
        // GIVEN - a pending report of its own path
        final String path = "ai/publish";
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String pendingUuid = generate(path, resultUuid, true, false, true);

        // WHEN - the model is asked to analyse it
        mockMvc.perform(post(API_REPORT + "/" + pendingUuid + "/ai")).andExpect(status().isAccepted());
        final AiJobStatus status = awaitFinished(pendingUuid);

        // THEN - the job is done and points at the report that carries the analysis
        assertThat(status).as("job status after the model answered").isEqualTo(AiJobStatus.DONE);
        final String publishedUuid = aiAnalysisService.status(pendingUuid).getResultUuid();
        assertThat(publishedUuid).as("uuid of the published report").isNotNull();

        // AND - it is a new version of the same path, one level above the pending one
        final ReportEntity pending = reportRepository.findOneByUuid(UUID.fromString(pendingUuid)).orElseThrow();
        final ReportEntity published = reportRepository.findOneByUuid(UUID.fromString(publishedUuid)).orElseThrow();
        assertThat(published.getPath()).as("path of the published report").isEqualTo(path);
        assertThat(published.getLevel()).as("level of the published report").isEqualTo(pending.getLevel() + 1);

        // AND - the analysed results moved under the new report and now carry the answers
        assertThat(CACHE_DIR.resolve(pendingUuid).resolve("results"))
            .as("results copy of the pending report after publication")
            .doesNotExist();
        assertThat(readJson(CACHE_DIR.resolve(publishedUuid).resolve("results").resolve(AI_SUMMARY))
            .path("status").asText())
            .as("analysis status in the published results")
            .isEqualTo("done");
    }

    @Test
    @DisplayName("should answer 200 and publish nothing when the analysis of a report is already done")
    void doesNotPublishASecondReportWhenTheAnalysisIsDone() throws Exception {
        // GIVEN - a report whose analysis has already been published
        final String path = "ai/repeat";
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String pendingUuid = generate(path, resultUuid, true, false, true);
        mockMvc.perform(post(API_REPORT + "/" + pendingUuid + "/ai")).andExpect(status().isAccepted());
        assertThat(awaitFinished(pendingUuid)).as("first run of the model").isEqualTo(AiJobStatus.DONE);
        final String publishedUuid = aiAnalysisService.status(pendingUuid).getResultUuid();

        // WHEN - the published report is submitted for analysis again
        mockMvc.perform(post(API_REPORT + "/" + publishedUuid + "/ai"))
            // THEN - 200, not 202: there is nothing left to do
            .andExpect(status().isOk());

        // AND - no third report appeared for this path
        assertThat(reportRepository.findByPath(path))
            .as("reports of this path after the repeated request")
            .hasSize(2);
    }

    @Test
    @DisplayName("should fail the job and publish nothing when the model answers with unusable text")
    void failsTheJobWhenTheModelAnswersGarbage() throws Exception {
        // GIVEN - a pending report and a model that cannot produce the expected JSON
        final String path = "ai/garbage";
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String pendingUuid = generate(path, resultUuid, true, false, true);
        STUB.mode(OpenCodeStub.Mode.GARBAGE);

        // WHEN - the model is asked to analyse the report
        mockMvc.perform(post(API_REPORT + "/" + pendingUuid + "/ai")).andExpect(status().isAccepted());

        // THEN - the job fails and the pending report stays the only one of its path
        assertThat(awaitFinished(pendingUuid)).as("job status after an unusable answer").isEqualTo(AiJobStatus.ERROR);
        assertThat(reportRepository.findByPath(path))
            .as("reports of this path after a failed analysis")
            .hasSize(1);
    }

    @Test
    @DisplayName("should count a repeated failure as persistent and an unseen one as new against the previous run")
    void comparesTheRunWithThePreviousOne() throws Exception {
        // GIVEN - a first nightly run of this path with two failures, kept as the previous run
        final String path = "ai/previous";
        final String firstResults = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        generate(path, firstResults, true, false, true);

        // WHEN - the next run repeats one failure and brings a new one
        final String secondResults = AllureResultsFixture.write(RESULTS_DIR, 2, "alpha", "gamma");
        final String secondReport = generate(path, secondResults, true, false, true);

        // THEN - the diff separates the two
        final JsonNode previousRun = readJson(CACHE_DIR.resolve(secondReport).resolve("results").resolve(AI_SUMMARY))
            .path("previousRun");
        assertThat(previousRun.path("persistent").asInt())
            .as("failures that were already failing in the previous run")
            .isEqualTo(1);
        assertThat(previousRun.path("new").asInt())
            .as("failures unseen in the previous run")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("should not regenerate the report when a newer one already exists for the same path")
    void doesNotRegenerateWhenANewerReportExists() throws Exception {
        // GIVEN - a pending report that has since been superseded by a newer one of the same path
        final String path = "ai/race";
        final String firstResults = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String pendingUuid = generate(path, firstResults, true, false, true);
        final String newerResults = AllureResultsFixture.write(RESULTS_DIR, 2, "alpha", "beta");
        generate(path, newerResults, false, false, false);

        // WHEN - the analysis of the superseded report finishes
        mockMvc.perform(post(API_REPORT + "/" + pendingUuid + "/ai")).andExpect(status().isAccepted());

        // THEN - the job is done but deliberately published nothing
        assertThat(awaitFinished(pendingUuid)).as("job status of a superseded report").isEqualTo(AiJobStatus.DONE);
        assertThat(aiAnalysisService.status(pendingUuid).getResultUuid())
            .as("published report of a superseded analysis")
            .isNull();
        assertThat(reportRepository.findByPath(path))
            .as("reports of this path after the analysis of the older one")
            .hasSize(2);
    }

    @Test
    @DisplayName("should delete an old results copy whose report no longer exists when preparing the next analysis")
    void sweepsOldCopiesWithoutAReport() throws Exception {
        // GIVEN - a leftover copy of a report that is not in the database, older than any generation
        final Path orphan = orphanCopy(OLDER_THAN_SWEEP_AGE);

        // WHEN - any report is generated with the analysis
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");
        generate("ai/sweep", resultUuid, true, false, true);

        // THEN - the leftover is gone
        assertThat(orphan).as("old results copy of a report that no longer exists").doesNotExist();
    }

    @Test
    @DisplayName("should keep a fresh results copy without a report: its generation may still be uncommitted")
    void keepsFreshCopiesWithoutAReport() throws Exception {
        // GIVEN - a copy written moments ago, exactly what a generation in another thread looks like
        // before its transaction commits and its report row becomes visible
        final Path fresh = orphanCopy(Duration.ZERO);

        // WHEN - another generation sweeps the cache while preparing its own analysis
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");
        generate("ai/sweep-fresh", resultUuid, true, false, true);

        // THEN - the copy is untouched: deleting it would take the results out from under a job
        assertThat(fresh.resolve("ai-job.json"))
            .as("job file of a copy younger than the sweep age")
            .isRegularFile();
    }

    @Test
    @DisplayName("should answer 400 when the report uuid in the analysis request is malformed")
    void rejectsAMalformedUuid() throws Exception {
        // GIVEN - a path variable that is not a uuid at all
        // WHEN - it is submitted for analysis
        // THEN - 400 before anything touches the filesystem
        mockMvc.perform(post(API_REPORT + "/not-a-uuid/ai"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should finish the job at once and offer no analysis button when the run has no failures")
    void finishesTheJobAndHidesTheButtonWhenThereAreNoFailures() throws Exception {
        // GIVEN - a green run and, for comparison, a run that failed
        final String greenResults = AllureResultsFixture.write(RESULTS_DIR, 1);
        final String greenUuid = generate("ai/green", greenResults, true, false, true);
        final String failedResults = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");
        final String failedUuid = generate("ai/green-neighbour", failedResults, true, false, true);
        clearTemporaryPassword();

        // THEN - there is nothing to ask the model about, so the job is over before it starts
        assertThat(aiAnalysisService.status(greenUuid).getStatus())
            .as("job status of a run without a single failure")
            .isEqualTo(AiJobStatus.DONE);

        // WHEN - the grid is rendered for a user allowed to mutate
        final String html = mockMvc.perform(get("/app/reports").header(HttpHeaders.AUTHORIZATION, basicAuthHeader()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the green report carries no analysis form, while its failing neighbour on the very
        // same page does: the button is missing because the job is done, not because nothing renders
        assertThat(html)
            .as("analysis form of the report without failures")
            .doesNotContain(analysisFormAction(greenUuid));
        assertThat(html)
            .as("analysis form of the report with clusters, rendered by the same pass")
            .contains(analysisFormAction(failedUuid));
    }

    @Test
    @DisplayName("should render an analysis form in the reports grid that the server actually accepts")
    void gridOffersAWorkingAnalysisForm() throws Exception {
        // GIVEN - a pending report and an authenticated user allowed to mutate
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String pendingUuid = generate("ai/grid", resultUuid, true, false, true);
        clearTemporaryPassword();
        final String basicAuth = basicAuthHeader();

        // WHEN - the reports page is rendered for that user
        final String html = mockMvc.perform(get("/app/reports").header(HttpHeaders.AUTHORIZATION, basicAuth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the row carries a form posting to the analysis endpoint of this very report
        final Matcher form = Pattern.compile("action=\"(/app/reports/" + pendingUuid + "/ai)\"").matcher(html);
        assertThat(form.find()).as("analysis form of report '%s' in the rendered grid", pendingUuid).isTrue();

        // AND - that exact url is served: the button is wired, not decorative
        mockMvc.perform(post(form.group(1)).header(HttpHeaders.AUTHORIZATION, basicAuth).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(awaitFinished(pendingUuid)).as("job started from the grid button").isEqualTo(AiJobStatus.DONE);
    }

    //// PRIVATE ////

    /**
     * A cache directory of a report that is not in the database, aged by touching it back in time.
     *
     * @param age how long ago the copy was last written; {@link Duration#ZERO} is "just now"
     */
    private static Path orphanCopy(Duration age) throws IOException {
        final Path orphan = CACHE_DIR.resolve(UUID.randomUUID().toString());
        Files.createDirectories(orphan.resolve("results"));
        Files.writeString(orphan.resolve("ai-job.json"), "{\"status\":\"pending\"}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(age)));
        return orphan;
    }

    /** {@code POST /api/report}; returns the uuid of the created report. */
    private String generate(String path, String resultUuid, boolean deleteResults, boolean singleFile,
                            boolean aiAnalysis) throws Exception {
        final String[] segments = path.split("/");
        final String body = """
            {"reportSpec":{"path":["%s","%s"],"executorInfo":{"buildName":"nightly"}},
             "results":["%s"],"deleteResults":%b,"singleFile":%b,"aiAnalysis":%b}
            """.formatted(segments[0], segments[1], resultUuid, deleteResults, singleFile, aiAnalysis);
        final String response = mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("uuid").asText();
    }

    /** The {@code action} the grid renders for the analysis button of one report. */
    private static String analysisFormAction(String reportUuid) {
        return "action=\"/app/reports/" + reportUuid + "/ai\"";
    }

    /** Blocks until the worker leaves the in-flight states, or the timeout expires. */
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

    private JsonNode readJson(Path file) throws IOException {
        return objectMapper.readTree(file.toFile());
    }

    private void clearTemporaryPassword() {
        final UserEntity user = userRepository.findByUsername(ADMIN_USER).orElseThrow();
        user.setPasswordTemporary(false);
        userRepository.save(user);
    }

    private static String basicAuthHeader() {
        return "Basic " + Base64.getEncoder()
            .encodeToString((ADMIN_USER + ":" + ADMIN_PASS).getBytes(StandardCharsets.UTF_8));
    }
}
