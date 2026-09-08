package ru.iopump.qa.allure.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Writes a small but genuine {@code allure-results} directory: one failed {@code *-result.json} per
 * named scenario with the stack trace inline, one passing scenario, and the {@code executor.json}
 * the analysis needs to treat the directory as one CI run rather than a local folder that has been
 * accumulating for months.
 * <p>
 * Scenario names are also the {@code historyId}s, which is what the cross-run diff matches on:
 * repeating a name in the next run makes that failure "persistent", a new name makes it "new".
 */
final class AllureResultsFixture {

    /** Fixed start so both runs of a test land in the same analysis window. */
    private static final long RUN_START = 1756090000000L;
    private static final long STEP_MS = 1000L;

    private AllureResultsFixture() {
    }

    /**
     * @param resultsRoot the server results storage directory
     * @param buildOrder  CI build number written into {@code executor.json}
     * @param failed      scenario names that failed; each gets its own error and therefore its own cluster
     * @return uuid of the created results directory, as accepted by {@code POST /api/report}
     */
    static String write(Path resultsRoot, int buildOrder, String... failed) throws IOException {
        final String uuid = UUID.randomUUID().toString();
        final Path dir = resultsRoot.resolve(uuid);
        Files.createDirectories(dir);

        long start = RUN_START;
        for (String name : failed) {
            writeJson(dir, failedResult(name, start));
            start += STEP_MS;
        }
        writeJson(dir, passedResult(start));
        Files.writeString(dir.resolve("executor.json"), """
            {"name":"Jenkins","type":"jenkins","buildName":"nightly at-h2h #%d","buildOrder":%d,
             "buildUrl":"https://ci.example.com/job/nightly/%d/"}
            """.formatted(buildOrder, buildOrder, buildOrder), StandardCharsets.UTF_8);
        return uuid;
    }

    private static void writeJson(Path dir, String json) throws IOException {
        Files.writeString(dir.resolve(UUID.randomUUID() + "-result.json"), json, StandardCharsets.UTF_8);
    }

    private static String failedResult(String name, long start) {
        return """
            {"uuid":"%s","historyId":"%s","name":"Scenario %s","fullName":"suite.%s",
             "status":"failed","stage":"finished","start":%d,"stop":%d,
             "statusDetails":{
               "message":"%s: the service replied 500 instead of 200",
               "trace":"java.lang.AssertionError: %s: the service replied 500 instead of 200\\n\\tat steps.ApiSteps.checkResponse(ApiSteps.java:42)\\n\\tat suite.%s.run(%s.java:11)"},
             "labels":[{"name":"suite","value":"Api suite"},{"name":"feature","value":"%s"}],
             "links":[],
             "steps":[{"name":"When the request is sent","status":"passed","start":%d,"stop":%d},
                      {"name":"Then the response is 200","status":"failed","start":%d,"stop":%d,
                       "statusDetails":{"message":"%s: the service replied 500 instead of 200"}}]}
            """.formatted(UUID.randomUUID(), name, name, name,
            start, start + STEP_MS,
            name, name, name, name, name,
            start, start + 100, start + 100, start + STEP_MS,
            name);
    }

    private static String passedResult(long start) {
        return """
            {"uuid":"%s","historyId":"green","name":"Scenario green","fullName":"suite.green",
             "status":"passed","stage":"finished","start":%d,"stop":%d,
             "labels":[{"name":"suite","value":"Api suite"}],
             "steps":[{"name":"When the request is sent","status":"passed","start":%d,"stop":%d}]}
            """.formatted(UUID.randomUUID(), start, start + STEP_MS, start, start + STEP_MS);
    }
}
