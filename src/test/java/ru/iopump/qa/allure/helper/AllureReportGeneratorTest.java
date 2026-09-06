package ru.iopump.qa.allure.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanFactory;
import ru.iopump.qa.allure.config.BrandingService;
import ru.iopump.qa.allure.helper.plugin.BrandingPlugin;
import ru.iopump.qa.allure.properties.AllureProperties;
import ru.iopump.qa.allure.properties.TmsProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AllureReportGeneratorTest {
    @TempDir
    Path temp;

    @Test
    void generatesStandaloneReportWithEmbeddedResultsAttachmentsAndBranding() throws Exception {
        Path results = Files.createDirectory(temp.resolve("results"));
        Files.writeString(results.resolve("sample-result.json"), """
            {"uuid":"sample", "name":"Offline test", "status":"passed", "stage":"finished",
             "start":1000,"stop":2000,
             "attachments":[{"name":"Evidence","source":"evidence.txt","type":"text/plain"}]}
            """);
        Files.writeString(results.resolve("evidence.txt"), "offline attachment");
        BeanFactory factory = mock(BeanFactory.class);
        BrandingService branding = new BrandingService();
        when(factory.getBean(BrandingService.class)).thenReturn(branding);
        var generator = new AllureReportGenerator(List.of(new BrandingPlugin()),
            mock(AllureProperties.class), mock(TmsProperties.class), factory);
        Path output = temp.resolve("single");
        generator.generate(output, List.of(results), "http://localhost/report/", true);

        String html = Files.readString(output.resolve("index.html"));
        assertThat(html).contains("window.reportData =", "data:text/javascript;base64,",
            "data:text/css;base64,", "data:image/svg+xml;base64,", "brew-brand-inline",
            "d('data/test-cases/", "d('data/attachments/")
            .doesNotContain("href=\"brew-brand.css\"", "src=\"brew-brand.js\"", "href=\"favicon.svg\"");
        var embeddedData = Pattern.compile("d\\('([^']+)','([^']*)'\\)").matcher(html).results()
            .collect(java.util.stream.Collectors.toMap(match -> match.group(1),
                match -> new String(Base64.getDecoder().decode(match.group(2)), StandardCharsets.UTF_8)));
        assertThat(embeddedData.values()).anySatisfy(value -> assertThat(value).contains("Offline test"));
        assertThat(embeddedData.values()).contains("offline attachment");
        branding.applyBranding(output);
        assertThat(Files.readString(output.resolve("index.html"))).isEqualTo(html);

        Path regular = temp.resolve("regular");
        generator.generate(regular, List.of(results), "http://localhost/report/");
        assertThat(Files.readString(regular.resolve("index.html")))
            .doesNotContain("window.reportData =").contains("href=\"brew-brand.css\"");
        assertThat(regular.resolve("app.js")).exists();
        assertThat(regular.resolve("widgets/summary.json")).exists();
    }
}
