package com.supportiq.rag;

public record CompanyAiSettings(boolean enabled, String provider, String model, String language, String tone,
                                int maxChunks, double relevanceThreshold, boolean handoffOnUnknown,
                                String fallbackResponse) {
    public static CompanyAiSettings defaults() {
        return new CompanyAiSettings(true,"GEMINI","gemini-3.8-flash","English","Friendly and concise",4,.42,true,
                "I could not find that information in the approved company documents. I can connect you with a support agent.");
    }
}
