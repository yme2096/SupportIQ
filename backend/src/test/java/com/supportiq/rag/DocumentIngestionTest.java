package com.supportiq.rag;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocumentIngestionTest {
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void usesDeterministicLocalEmbeddingsWhenGeminiKeyIsMissing() {
        GeminiEmbeddingProvider provider = new GeminiEmbeddingProvider();
        ReflectionTestUtils.setField(provider, "provider", "gemini");
        ReflectionTestUtils.setField(provider, "embeddingKey", "");
        ReflectionTestUtils.setField(provider, "generationKey", "");
        ReflectionTestUtils.setField(provider, "model", "gemini-embedding-2");

        String text = "title: return-policy.md | text: returns are accepted within thirty days";
        float[] first = provider.embedDocuments(List.of(text)).getFirst();
        float[] repeat = provider.embedDocuments(List.of(text)).getFirst();
        float[] query = provider.embedQuery("Are returns accepted within thirty days?");

        assertTrue(provider.available());
        assertEquals("LOCAL_HASH", provider.providerName());
        assertEquals("supportiq-hash-768-v1", provider.modelName());
        assertEquals(768, first.length);
        assertArrayEquals(first, repeat);
        assertTrue(cosine(first, query) > 0.2);
    }

    private double cosine(float[] a, float[] b) {
        double result = 0;
        for (int i=0; i<a.length; i++) result += (double)a[i] * b[i];
        return result;
    }

    @Test
    void extractsMarkdownSectionsAndNormalizesText() throws Exception {
        var result = extractor.extract("policy.md", "# Returns\r\nReturns are accepted within 30 days.\r\n\r\n## Shipping\r\nDelivery takes five days.".getBytes(StandardCharsets.UTF_8));
        assertTrue(result.text().contains("Returns are accepted"));
        assertEquals("Returns", result.segments().getFirst().sectionTitle());
        assertEquals("Shipping", result.segments().getLast().sectionTitle());
    }

    @Test
    void extractsPdfTextWithPageReferences() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            for (String text : List.of("First policy page", "Second policy page")) {
                PDPage page = new PDPage(); pdf.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700); stream.showText(text); stream.endText();
                }
            }
            pdf.save(bytes);
        }
        var result = extractor.extract("policy.pdf", bytes.toByteArray());
        assertTrue(result.text().contains("First policy page"));
        assertTrue(result.segments().stream().anyMatch(s -> Integer.valueOf(2).equals(s.pageNumber()) && s.text().contains("Second policy page")));
    }

    @Test
    void extractsDocxAndRejectsInvalidOrEmptyFiles() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Warranty lasts two years</w:t></w:r></w:p><w:sectPr/></w:body></w:document>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        assertTrue(extractor.extract("warranty.docx", bytes.toByteArray()).text().contains("Warranty lasts two years"));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract("empty.txt", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract("broken.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract("unsupported.csv", "a,b".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void chunksRespectConfiguredBoundsAndKeepPageSectionMetadata() {
        KnowledgeChunker chunker = new KnowledgeChunker(400, 40);
        String longText = "A customer may request a return within thirty days. ".repeat(30);
        var chunks = chunker.chunk(List.of(new DocumentTextExtractor.SourceSegment(4, "Returns", longText)));
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= 400 && c.pageNumber() == 4 && "Returns".equals(c.sectionTitle())));
        assertEquals(0, chunks.getFirst().index());
    }
}
