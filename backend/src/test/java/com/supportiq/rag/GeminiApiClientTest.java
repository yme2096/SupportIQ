package com.supportiq.rag;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiApiClientTest {
    private static final String URI = "https://gemini.test/v1beta/models/example:generateContent";

    @Test
    void postsWithServerSideKeyAndReturnsJson() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiApiClient client = new GeminiApiClient(builder.build());
        server.expect(requestTo(URI)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "secret-test-value"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertEquals(Boolean.TRUE, client.postJson(URI, "secret-test-value", Map.of("prompt", "private test")).get("ok"));
        server.verify();
    }

    @Test
    void retriesRateLimitsOnlyWithinTheConfiguredBound() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiApiClient client = new GeminiApiClient(builder.build());
        server.expect(org.springframework.test.web.client.ExpectedCount.twice(), requestTo(URI))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body("{}"));
        server.expect(requestTo(URI)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertEquals(Boolean.TRUE, client.postJson(URI, "test", Map.of()).get("ok"));
        server.verify();
    }

    @Test
    void rejectsInvalidKeyWithoutRetryingOrLeakingProviderBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiApiClient client = new GeminiApiClient(builder.build());
        server.expect(requestTo(URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"secret-test-value customer@example.test\"}"));

        var error = assertThrows(GeminiApiClient.GeminiApiException.class,
                () -> client.postJson(URI, "secret-test-value", Map.of()));
        assertEquals(401, error.statusCode());
        assertFalse(error.getMessage().contains("secret-test-value"));
        assertFalse(error.getMessage().contains("customer@example.test"));
        server.verify();
    }

    @Test
    void boundsServerErrorRetriesToThreeAttempts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiApiClient client = new GeminiApiClient(builder.build());
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3), requestTo(URI))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body("{}"));

        var error = assertThrows(GeminiApiClient.GeminiApiException.class,
                () -> client.postJson(URI, "test", Map.of()));
        assertEquals(500, error.statusCode());
        server.verify();
    }

    @Test
    void boundsNetworkFailureRetriesAndDoesNotExposeExceptionText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiApiClient client = new GeminiApiClient(builder.build());
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3), requestTo(URI))
                .andRespond(withException(new IOException("secret-test-value customer@example.test")));

        var error = assertThrows(GeminiApiClient.GeminiApiException.class,
                () -> client.postJson(URI, "secret-test-value", Map.of()));
        assertNull(error.statusCode());
        assertFalse(error.getMessage().contains("secret-test-value"));
        assertFalse(error.getMessage().contains("customer@example.test"));
        server.verify();
    }

    @Test
    void handlesTimeoutFailureWithoutUnboundedRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiApiClient client = new GeminiApiClient(builder.build());
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3), requestTo(URI))
                .andRespond(withException(new SocketTimeoutException("timed out")));

        var error = assertThrows(GeminiApiClient.GeminiApiException.class,
                () -> client.postJson(URI, "test", Map.of()));
        assertTrue(error.getMessage().contains("timeout"));
        server.verify();
    }

    @Test
    void turnsMalformedJsonIntoSafeNonRetryableFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiApiClient client = new GeminiApiClient(builder.build());
        server.expect(requestTo(URI)).andRespond(withSuccess("{malformed", MediaType.APPLICATION_JSON));

        var error = assertThrows(GeminiApiClient.GeminiApiException.class,
                () -> client.postJson(URI, "test", Map.of()));
        assertEquals("Gemini returned an invalid response.", error.getMessage());
        server.verify();
    }
}
