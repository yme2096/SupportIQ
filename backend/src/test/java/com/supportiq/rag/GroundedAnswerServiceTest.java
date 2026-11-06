package com.supportiq.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GroundedAnswerServiceTest {
    private final GroundedAnswerService service=new GroundedAnswerService();

    @Test
    void answersFromMatchingSourceSentenceWhenGeminiIsNotConfigured() {
        KnowledgeChunk source=new KnowledgeChunk(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Returns.txt",0,null,null,
                "Returns are accepted within fourteen days. Please contact support for a return label.",0.82);
        CompanyAiSettings settings=new CompanyAiSettings(true,"GEMINI","gemini-3.8-flash","English","Friendly",4,0.42,true,"I could not find that information.");

        GroundedAnswerService.AnswerResult result=service.answer("Are returns accepted within fourteen days?",List.of(source),settings);

        assertFalse(result.handoffRecommended());
        assertEquals("According to the approved company information: Returns are accepted within fourteen days.",result.answer());
    }

    @Test
    void keepsUnknownQuestionsOnSafeFallbackWhenGeminiIsNotConfigured() {
        KnowledgeChunk source=new KnowledgeChunk(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Returns.txt",0,null,null,
                "Returns are accepted within fourteen days.",0.82);
        CompanyAiSettings settings=new CompanyAiSettings(true,"GEMINI","gemini-3.8-flash","English","Friendly",4,0.42,true,"I could not confirm that.");

        GroundedAnswerService.AnswerResult result=service.answer("Can I return an item after a year?",List.of(source),settings);

        assertTrue(result.handoffRecommended());
        assertEquals("I could not confirm that.",result.answer());
        assertEquals(GroundedAnswerService.NO_MATCHING_SOURCE_REASON,result.reason());
    }

    @Test
    void keepsUnrelatedQuestionsOnSafeFallbackWhenOnlyTheCompanyNameMatches() {
        KnowledgeChunk source=new KnowledgeChunk(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Beacon-policy.md",0,null,null,
                "Beacon-only policy: premium exchanges are allowed within 21 days.",0.82);
        CompanyAiSettings settings=new CompanyAiSettings(true,"GEMINI","gemini-3.8-flash","English","Friendly",4,0.42,true,"I cannot confirm that from the approved documents.");

        GroundedAnswerService.AnswerResult result=service.answer("Does Beacon provide certified interplanetary organ donation coverage?",List.of(source),settings);

        assertTrue(result.handoffRecommended());
        assertEquals("I cannot confirm that from the approved documents.",result.answer());
        assertEquals(GroundedAnswerService.NO_MATCHING_SOURCE_REASON,result.reason());
    }
}
