package com.supportiq.rag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Shared bounded, credential-safe HTTP boundary for Gemini calls. */
@Component
public class GeminiApiClient {
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MILLIS = {200, 500};
    private final RestClient http;

    @Autowired
    public GeminiApiClient(RestClient.Builder builder) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdk);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.http = builder.requestFactory(requestFactory).build();
    }

    GeminiApiClient(RestClient http) {
        this.http = http;
    }

    public Map<?, ?> postJson(String uri, String apiKey, Object payload) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return http.post().uri(uri)
                        .header("x-goog-api-key", apiKey)
                        .body(payload)
                        .retrieve()
                        .body(Map.class);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (!retryable(status) || attempt == MAX_ATTEMPTS) {
                    throw new GeminiApiException(status);
                }
                pause(attempt);
            } catch (ResourceAccessException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new GeminiApiException("Gemini request failed after bounded retries (network or timeout).");
                }
                pause(attempt);
            } catch (RestClientException ex) {
                // Includes malformed JSON and other response decoding failures. Retrying the same response
                // cannot fix it, and the provider body may contain customer-supplied text.
                throw new GeminiApiException("Gemini returned an invalid response.");
            }
        }
        throw new GeminiApiException("Gemini request failed after bounded retries.");
    }

    private boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(BACKOFF_MILLIS[Math.min(attempt - 1, BACKOFF_MILLIS.length - 1)]);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GeminiApiException("Gemini request was interrupted.");
        }
    }

    public static final class GeminiApiException extends RuntimeException {
        private final Integer statusCode;

        GeminiApiException(int statusCode) {
            super("Gemini request failed with HTTP " + statusCode + ".");
            this.statusCode = statusCode;
        }

        GeminiApiException(String safeMessage) {
            super(safeMessage);
            this.statusCode = null;
        }

        public Integer statusCode() {
            return statusCode;
        }
    }
}
