package ru.iopump.qa.allure.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the AI-analysis add-on. It exists so {@link AiProperties} can stay an immutable
 * {@code @ConstructorBinding} type - that requires an explicit registration - without extending the
 * {@code @EnableConfigurationProperties} list on {@link ru.iopump.qa.allure.Application}: the add-on
 * is meant to be additive to the server it plugs into.
 * <p>
 * The beans themselves ({@link AiAnalysisService}, the controllers) are ordinary component-scanned
 * Spring beans and need nothing here.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
}
