package com.supportiq.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class DocumentTextExtractor {
    public ExtractedDocument extractText(String text) {
        String normalized=normalize(text);
        if(normalized.isBlank()) throw new IllegalArgumentException("No readable text was found in this legacy document.");
        List<SourceSegment> initial=normalized.lines().map(line->new SourceSegment(null,null,line)).toList();
        return new ExtractedDocument(normalized,assignSections(initial));
    }

    public ExtractedDocument extract(String filename, byte[] bytes) throws Exception {
        String lower = filename.toLowerCase(Locale.ROOT);
        List<SourceSegment> segments;
        if (lower.endsWith(".pdf")) segments = extractPdf(bytes);
        else if (lower.endsWith(".docx")) segments = extractOffice(filename, bytes);
        else if (lower.endsWith(".txt") || lower.endsWith(".md")) segments = extractPlain(bytes);
        else throw new IllegalArgumentException("Supported document formats are PDF, DOCX, TXT, and MD.");
        List<SourceSegment> clean = segments.stream().map(s -> new SourceSegment(s.pageNumber(), null,
                normalize(s.text()))).filter(s -> !s.text().isBlank()).toList();
        String text = String.join("\n\n", clean.stream().map(SourceSegment::text).toList()).trim();
        if (text.isBlank()) throw new IllegalArgumentException("No readable text was found. Scanned or empty documents are not supported.");
        return new ExtractedDocument(text, assignSections(clean));
    }

    private List<SourceSegment> extractPdf(byte[] bytes) throws Exception {
        if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
            throw new IllegalArgumentException("The file does not contain a valid PDF signature.");
        List<SourceSegment> result = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw new IllegalArgumentException("Password-protected PDFs cannot be processed.");
            if (document.getNumberOfPages() > 500) throw new IllegalArgumentException("PDFs are limited to 500 pages.");
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page=1; page<=document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                result.add(new SourceSegment(page, null, stripper.getText(document)));
            }
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("The PDF could not be opened or is damaged."); }
        return result;
    }

    private List<SourceSegment> extractOffice(String filename, byte[] bytes) throws Exception {
        if (!containsWordDocument(bytes)) throw new IllegalArgumentException("The file is not a valid DOCX document.");
        Metadata metadata = new Metadata();
        metadata.set("resourceName", filename);
        ContentHandler handler = new BodyContentHandler(2_000_000);
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            new AutoDetectParser().parse(input, handler, metadata, new org.apache.tika.parser.ParseContext());
        } catch (Exception ex) { throw new IllegalArgumentException("The DOCX file could not be parsed."); }
        return List.of(new SourceSegment(null, null, handler.toString()));
    }

    private boolean containsWordDocument(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) if ("word/document.xml".equals(entry.getName())) return true;
        }
        return false;
    }

    private List<SourceSegment> extractPlain(byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (text.indexOf('\u0000') >= 0) throw new IllegalArgumentException("The text document contains binary data.");
        return List.of(new SourceSegment(null, null, text));
    }

    private List<SourceSegment> assignSections(List<SourceSegment> segments) {
        List<SourceSegment> output = new ArrayList<>();
        String section = null;
        for (SourceSegment segment : segments) {
            for (String line : segment.text().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) continue;
                String heading = heading(trimmed);
                if (heading != null) { section = heading; continue; }
                output.add(new SourceSegment(segment.pageNumber(), section, trimmed));
            }
        }
        return output;
    }

    private String heading(String line) {
        if (line.matches("^#{1,6}\\s+.{1,200}$")) return line.replaceFirst("^#{1,6}\\s+", "").trim();
        if (line.length() <= 100 && (line.endsWith(":") || line.matches("[A-Z0-9][A-Z0-9 /&()'-]{2,80}"))) return line.replaceAll(":$", "");
        return null;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    public record SourceSegment(Integer pageNumber, String sectionTitle, String text) {}
    public record ExtractedDocument(String text, List<SourceSegment> segments) {}
}
