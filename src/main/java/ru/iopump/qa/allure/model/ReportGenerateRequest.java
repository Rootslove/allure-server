package ru.iopump.qa.allure.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import ru.iopump.qa.allure.service.PathUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
public class ReportGenerateRequest {
    /**
     * Information about report. Will ba added to generated report.
     */
    @NotNull(message = "reportSpec is required")
    @Valid
    ReportSpec reportSpec;
    /**
     * Result UUID collection to generate new report.
     */
    @NotEmpty(message = "results must not be empty")
    List<@NotBlank(message = "result UUID must not be blank") @Pattern(regexp = PathUtil.UUID_PATTERN, message = "result UUID must match UUID pattern") String> results;
    /**
     * Delete result after generation.
     */
    boolean deleteResults = true;

    /** Generate a standalone index.html, equivalent to allure generate --single-file. */
    @Schema(
        description = "Embed report data and assets in a standalone index.html", defaultValue = "false")
    boolean singleFile = false;

    @JsonIgnore
    public List<Path> getResultsAsPath(@NonNull Path baseResultDir) {
        return results.stream().map(p -> baseResultDir.resolve(Paths.get(p))).collect(Collectors.toUnmodifiableList());
    }
}
