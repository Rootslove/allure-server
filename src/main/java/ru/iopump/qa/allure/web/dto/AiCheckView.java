package ru.iopump.qa.allure.web.dto;

import ru.iopump.qa.allure.ai.AiConnectionCheckService;

import java.util.List;

/**
 * Outcome of "Check connection" for {@code admin/settings/index.jte}: whether OpenCode answered,
 * what it said, which providers it offers and whether the provider/model pair of the form is among
 * them. Present in the model only right after the button is pressed - nothing is stored.
 *
 * @param ok         whether OpenCode answered at all (drives the green/red label)
 * @param message    human-readable outcome
 * @param providers  providers OpenCode reports, empty when it did not answer
 * @param modelFound whether the provider/model pair of the form was found
 */
public record AiCheckView(boolean ok, String message, List<Provider> providers, boolean modelFound) {

    public static AiCheckView from(AiConnectionCheckService.CheckResult result) {
        return new AiCheckView(
            result.ok(),
            result.message(),
            result.providers().stream().map(p -> new Provider(p.id(), p.models())).toList(),
            result.modelFound()
        );
    }

    /**
     * @param id     provider id as OpenCode reports it
     * @param models how many models it offers
     */
    public record Provider(String id, int models) {
    }
}
