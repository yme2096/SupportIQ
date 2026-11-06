package com.supportiq.rag;

import java.util.UUID;

public record KnowledgeChunk(UUID id, UUID companyId, UUID documentId, String documentName,
                             int chunkIndex, Integer pageNumber, String sectionTitle,
                             String text, double similarity) {}
