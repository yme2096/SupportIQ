package com.supportiq.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeChunker {
    private final int maxChars;
    private final int overlap;

    public KnowledgeChunker(@Value("${app.ai.chunk-size:1800}") int maxChars,
                            @Value("${app.ai.chunk-overlap:240}") int overlap) {
        this.maxChars = Math.max(400, Math.min(maxChars, 6000));
        this.overlap = Math.max(0, Math.min(overlap, this.maxChars / 3));
    }

    public List<ChunkDraft> chunk(List<DocumentTextExtractor.SourceSegment> segments) {
        List<ChunkDraft> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < segments.size()) {
            var first = segments.get(cursor);
            Integer page = first.pageNumber(); String section = first.sectionTitle();
            StringBuilder group = new StringBuilder();
            while (cursor < segments.size() && java.util.Objects.equals(page, segments.get(cursor).pageNumber()) &&
                    java.util.Objects.equals(section, segments.get(cursor).sectionTitle())) {
                String text = segments.get(cursor++).text().trim();
                if (!text.isBlank()) { if (group.length() > 0) group.append(' '); group.append(text); }
            }
            String text = group.toString();
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(text.length(), start + maxChars);
                if (end < text.length()) end = boundary(text, end);
                String piece = text.substring(start, end).trim();
                if (!piece.isBlank()) add(result, piece, page, section);
                if (end >= text.length()) break;
                start = Math.max(start + 1, end - overlap);
                while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
            }
        }
        return result;
    }

    private void add(List<ChunkDraft> output, String value, Integer page, String section) {
        output.add(new ChunkDraft(output.size(), page, section, value.trim()));
    }
    private int boundary(String text, int end) {
        int floor = Math.max(0, end - maxChars / 5);
        int space = text.lastIndexOf(' ', end);
        int punctuation = Math.max(text.lastIndexOf('.', end), Math.max(text.lastIndexOf('?', end), text.lastIndexOf('!', end)));
        if (punctuation > floor) return punctuation + 1;
        return space > floor ? space : end;
    }

    public record ChunkDraft(int index, Integer pageNumber, String sectionTitle, String text) {}
}
