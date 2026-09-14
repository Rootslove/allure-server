package ru.iopump.qa.allure.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.iopump.qa.allure.ai.AiAnalysisService;
import ru.iopump.qa.allure.ai.AiJob;
import ru.iopump.qa.allure.ai.AiJobStatus;
import ru.iopump.qa.allure.model.AiJobResponse;
import ru.iopump.qa.allure.properties.AllureProperties;
import ru.iopump.qa.allure.service.PathUtil;

import static ru.iopump.qa.allure.helper.Util.url;

/**
 * The second button of the AI analysis: start the model over a report that was generated with
 * {@code "aiAnalysis": true} and ask for the state of that run. Kept apart from
 * {@link AllureReportController} so the add-on adds no dependency to the report API.
 * <p>
 * Authorization needs no extra rule: {@code /api/**} is already covered by the API matcher in
 * {@code SecurityConfiguration}. The uuid is pattern-validated for the same reason as in
 * {@link AllureReportController#deleteReport(String)} - it becomes a directory name under the
 * analysis cache and an unvalidated value would walk out of it.
 */
@RequiredArgsConstructor
@RestController
@Slf4j
@Validated
@RequestMapping(path = "/api/report")
public class AiJobController {

    private final AiAnalysisService aiAnalysisService;
    private final AllureProperties allureProperties;

    @Operation(summary = "Start the model analysis of a generated report")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Accepted: the job is queued or already running"),
        @ApiResponse(responseCode = "200", description = "Nothing to do: the analysis is already finished"),
        @ApiResponse(responseCode = "404", description = "This report has no results copy to analyse")
    })
    @PostMapping("/{uuid}/ai")
    public ResponseEntity<AiJobResponse> startAiAnalysis(
        @Parameter(description = "UUID of the report to analyse", required = true)
        @PathVariable @NotBlank @Pattern(regexp = PathUtil.UUID_PATTERN) String uuid
    ) {
        // The base url must be taken here, in the request thread: the worker has no request and
        // Util.url would fail there, yet the regenerated report needs absolute links.
        final AiJob job = aiAnalysisService.enqueue(uuid, url(allureProperties));
        final boolean accepted = job.getStatus() == AiJobStatus.QUEUED || job.getStatus() == AiJobStatus.RUNNING;
        return ResponseEntity.status(accepted ? HttpStatus.ACCEPTED : HttpStatus.OK).body(AiJobResponse.of(job));
    }

    @Operation(summary = "State of the model analysis of a generated report")
    @GetMapping("/{uuid}/ai")
    public AiJobResponse aiAnalysisStatus(
        @Parameter(description = "UUID of the report", required = true)
        @PathVariable @NotBlank @Pattern(regexp = PathUtil.UUID_PATTERN) String uuid
    ) {
        return AiJobResponse.of(aiAnalysisService.status(uuid));
    }
}
