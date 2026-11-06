package com.supportiq.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GeminiEmbeddingProvider implements EmbeddingProvider {
    private static final int DIMENSIONS = 768;
    private static final int BATCH_SIZE = 50;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final Set<String> STOP = Set.of("the","and","for","does","how","what","when","where","your","you","are","can","could","would","please","with","this","that","have","has","from","about","will","into","there","their","title","text","task","search","result","query");
    private final GeminiApiClient api;
    @Value("${app.ai.embedding-provider:gemini}") private String provider;
    @Value("${app.ai.embedding-api-key:}") private String embeddingKey;
    @Value("${app.gemini.api-key:}") private String generationKey;
    @Value("${app.ai.embedding-model:gemini-embedding-2}") private String model;
    @Value("${app.gemini.url}") private String base;

    @Autowired
    public GeminiEmbeddingProvider(GeminiApiClient api) { this.api = api; }

    GeminiEmbeddingProvider() { this(new GeminiApiClient(RestClient.create())); }

    private String key() { return embeddingKey == null || embeddingKey.isBlank() ? generationKey : embeddingKey; }
    private boolean geminiAvailable() { return "gemini".equalsIgnoreCase(provider) && key() != null && !key().isBlank(); }
    private boolean localFallback() { return "local".equalsIgnoreCase(provider) || ("gemini".equalsIgnoreCase(provider) && !geminiAvailable()); }
    @Override public boolean available() { return geminiAvailable() || localFallback(); }
    @Override public String providerName() { return geminiAvailable() ? "GEMINI" : "LOCAL_HASH"; }
    @Override public String modelName() { return geminiAvailable() ? model : "supportiq-hash-768-v1"; }

    @Override
    public List<float[]> embedDocuments(List<String> titledTexts) {
        if (localFallback()) return titledTexts.stream().map(this::localVector).toList();
        if (!available()) throw new IllegalStateException("Gemini embeddings are not configured. Set GEMINI_API_KEY or GEMINI_EMBEDDING_API_KEY.");
        List<float[]> all = new ArrayList<>();
        for (int start=0; start<titledTexts.size(); start += BATCH_SIZE) {
            List<String> batch = titledTexts.subList(start, Math.min(titledTexts.size(), start + BATCH_SIZE));
            List<Map<String,Object>> requests = batch.stream().map(text -> Map.<String,Object>of(
                    "model", "models/" + model,
                    "content", Map.of("parts", List.of(Map.of("text", text))),
                    "output_dimensionality", DIMENSIONS)).toList();
            Map<?,?> response = api.postJson(base + "/" + model + ":batchEmbedContents", key(), Map.of("requests", requests));
            Object raw = response == null ? null : response.get("embeddings");
            if (!(raw instanceof List<?> embeddings) || embeddings.size() != batch.size())
                throw new IllegalStateException("Gemini returned an incomplete embedding batch.");
            for (Object item : embeddings) {
                Object values = item instanceof Map<?,?> map ? map.get("values") : null;
                if (!(values instanceof List<?> vector) || vector.size() != DIMENSIONS)
                    throw new IllegalStateException("Gemini returned an embedding with an unexpected dimension.");
                float[] parsed = new float[DIMENSIONS];
                for (int i=0; i<DIMENSIONS; i++) {
                    Object value = vector.get(i);
                    if (!(value instanceof Number number) || !Float.isFinite(number.floatValue()))
                        throw new IllegalStateException("Gemini returned an invalid embedding value.");
                    parsed[i] = number.floatValue();
                }
                all.add(parsed);
            }
        }
        return all;
    }

    @Override
    public float[] embedQuery(String query) {
        if (localFallback()) return localVector(query);
        String task = "task: search result | query: " + Objects.requireNonNull(query).trim();
        return embedDocuments(List.of(task)).getFirst();
    }

    private float[] localVector(String text) {
        float[] vector = new float[DIMENSIONS];
        Matcher matcher = TOKEN.matcher(Objects.toString(text, "").toLowerCase(Locale.ROOT));
        String previous = null;
        while (matcher.find()) {
            String token = matcher.group();
            if (STOP.contains(token)) continue;
            addFeature(vector, token, 1.0f);
            if (previous != null) addFeature(vector, previous + "_" + token, 0.55f);
            previous = token;
        }
        double norm = 0;
        for (float value : vector) norm += (double) value * value;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= (float) norm;
        return vector;
    }

    private void addFeature(float[] vector, String feature, float weight) {
        int hash = feature.hashCode();
        int index = Math.floorMod(hash, vector.length);
        vector[index] += (hash < 0 ? -weight : weight);
    }
}
