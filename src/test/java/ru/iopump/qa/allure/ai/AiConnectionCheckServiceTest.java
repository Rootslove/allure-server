package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the "Check connection" button says about an address before it says anything about OpenCode.
 * <p>
 * An address the client cannot use must be named as such: an admin who typed a host without a
 * scheme, or a scheme without a host, has to read that the address is wrong and not that some host
 * is unreachable - the second sends them looking at a firewall for a typo.
 * <p>
 * The timeouts are asserted on the objects the check really builds: they are the only reason a
 * wrong address comes back within seconds instead of holding the page.
 */
@DisplayName("AiConnectionCheckService")
class AiConnectionCheckServiceTest {

    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);
    private static final String PROVIDER = "lmstudio";
    private static final String MODEL = "qwen3.8";

    private final AiConnectionCheckService service = new AiConnectionCheckService(new ObjectMapper());

    @Test
    @DisplayName("should reject a host without a scheme instead of calling anything")
    void rejectsAnAddressWithoutAScheme() {
        // GIVEN - the address as an admin types it from memory, without http://
        final String typed = "127.0.0.1:4096";

        // WHEN - the check button is pressed
        final AiConnectionCheckService.CheckResult result = service.check(typed, PROVIDER, MODEL);

        // THEN - it fails on the address itself, and says so
        assertThat(result.ok()).as("outcome of a schemeless address").isFalse();
        assertThat(result.message()).as("message of a schemeless address").startsWith("Not a valid URL");
        assertThat(result.message()).as("message of a schemeless address").contains(typed);
        assertThat(result.providers()).as("providers of a failed check").isEmpty();
    }

    @Test
    @DisplayName("should reject a scheme without a host instead of inventing the host 'config'")
    void rejectsAnAddressWithoutAHost() {
        // GIVEN - a half-typed address: the path of the check would make it a valid url with the
        // host 'config', which is exactly the misleading answer this test exists to prevent
        final String typed = "http://";

        // WHEN - the check button is pressed
        final AiConnectionCheckService.CheckResult result = service.check(typed, PROVIDER, MODEL);

        // THEN - it is the address that is wrong, not a host that does not answer
        assertThat(result.ok()).as("outcome of an address without a host").isFalse();
        assertThat(result.message()).as("message of an address without a host").startsWith("Not a valid URL");
        assertThat(result.message()).as("message must not name a host nobody typed").doesNotContain("config");
    }

    @Test
    @DisplayName("should build the client and the request with the five-second timeouts")
    void buildsTheClientAndTheRequestWithShortTimeouts() {
        // GIVEN - the address of a reachable OpenCode
        final URI uri = URI.create("http://127.0.0.1:4096/config/providers");

        // WHEN - the check builds what it sends the request with
        final HttpRequest request = AiConnectionCheckService.providersRequest(uri);

        // THEN - both timeouts are the short ones a browser request can afford
        try (HttpClient client = AiConnectionCheckService.newClient()) {
            assertThat(client.connectTimeout()).as("connect timeout of the check").contains(FIVE_SECONDS);
        }
        assertThat(request.timeout()).as("request timeout of the check").contains(FIVE_SECONDS);
        assertThat(request.method()).as("method of the check").isEqualTo("GET");
        assertThat(request.uri()).as("url of the check").isEqualTo(uri);
    }
}
