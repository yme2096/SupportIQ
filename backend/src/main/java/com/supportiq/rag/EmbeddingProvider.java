package com.supportiq.rag;

import java.util.List;

public interface EmbeddingProvider {
    boolean available();
    String providerName();
    String modelName();
    List<float[]> embedDocuments(List<String> titledTexts);
    float[] embedQuery(String query);
}
