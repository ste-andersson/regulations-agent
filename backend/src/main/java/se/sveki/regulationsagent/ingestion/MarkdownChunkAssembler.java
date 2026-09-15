package se.sveki.regulationsagent.ingestion;

import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.ast.Heading;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a converted {@code docs/sources/*.md} file into {@link Chunk}s, ready for embedding.
 * <p>
 * This is the sole ingestion parser for the whole system: PDF and HTML never reach the backend
 * (see docs/plan.md, "Source documents → Markdown"). Every source document, regardless of its original
 * numbering style (BRR's "B4.5.1.1", WRRC/Nordic's plain "2.1.1", or ILHC's un-numbered headings),
 * is represented identically once converted, so this class only ever has to understand Markdown
 * headings and tables.
 * <p>
 * Page numbers are recovered from {@code <!-- page:N -->} marker comments inserted at each page
 * break during conversion (see the conversion notes in each docs/sources/*.md file). Documents with
 * no page markers (e.g. the ILHC web page) simply produce chunks with a {@code null} page number.
 */
@Component
public class MarkdownChunkAssembler {

    private static final Pattern PAGE_MARKER = Pattern.compile("<!--\\s*page:(\\d+)\\s*-->");
    private static final Pattern SECTION_NUMBER = Pattern.compile("^([A-Z]?\\d+(?:\\.\\d+)*)\\.?\\s+(.+)$");
    private static final int MAX_CHUNK_CHARS = 3200; // roughly 500-800 tokens

    private final Parser markdownParser;

    public MarkdownChunkAssembler() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        this.markdownParser = Parser.builder(options).build();
    }

    public List<Chunk> assemble(String documentMarkdown, DocumentFrontMatter meta) {
        List<Segment> segments = splitByPageMarkers(documentMarkdown);
        List<HeadingEntry> headingStack = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        Integer bufferPage = null;
        List<Chunk> chunks = new ArrayList<>();
        int[] chunkIndex = {0};

        for (Segment segment : segments) {
            Document document = markdownParser.parse(segment.text());
            for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
                if (node instanceof Heading heading) {
                    flush(chunks, chunkIndex, buffer, bufferPage, headingStack, meta);
                    buffer = new StringBuilder();
                    bufferPage = null;
                    updateHeadingStack(headingStack, heading);
                } else if (node instanceof TableBlock) {
                    // Tables are atomic chunks: never split mid-row, and any accumulated
                    // surrounding text is flushed first so the table stands on its own.
                    flush(chunks, chunkIndex, buffer, bufferPage, headingStack, meta);
                    buffer = new StringBuilder();
                    bufferPage = null;
                    chunks.add(buildChunk(chunkIndex, headingStack, meta, ChunkType.TABLE,
                            node.getChars().toString().strip(), segment.pageNumber()));
                } else {
                    if (buffer.isEmpty()) {
                        bufferPage = segment.pageNumber();
                    }
                    buffer.append(node.getChars().toString()).append("\n\n");
                }
            }
        }
        flush(chunks, chunkIndex, buffer, bufferPage, headingStack, meta);
        return chunks;
    }

    private void flush(List<Chunk> chunks, int[] chunkIndex, StringBuilder buffer, Integer bufferPage,
                        List<HeadingEntry> headingStack, DocumentFrontMatter meta) {
        String content = buffer.toString().strip();
        if (content.isEmpty()) {
            return;
        }
        for (String part : splitToSizeBudget(content)) {
            chunks.add(buildChunk(chunkIndex, headingStack, meta, classify(headingStack), part, bufferPage));
        }
    }

    private Chunk buildChunk(int[] chunkIndex, List<HeadingEntry> headingStack, DocumentFrontMatter meta,
                              ChunkType chunkType, String text, Integer pageNumber) {
        String sectionNumber = headingStack.isEmpty() ? null : headingStack.get(headingStack.size() - 1).number();
        String sectionTitle = headingStack.isEmpty() ? null : headingStack.get(headingStack.size() - 1).title();
        String sectionPath = buildSectionPath(headingStack);
        String breadcrumb = sectionPath == null ? "" : sectionPath + "\n\n";
        int index = chunkIndex[0]++;
        String idSuffix = sectionNumber != null ? sectionNumber : String.valueOf(index);
        String id = meta.documentId() + "#" + idSuffix + "#" + String.format("%04d", index);
        return new Chunk(
                id,
                meta.documentId(),
                meta.title(),
                meta.federation(),
                meta.danceTypes(),
                meta.version(),
                meta.effectiveDate(),
                meta.sourceType(),
                meta.sourceLanguage(),
                sectionNumber,
                sectionTitle,
                sectionPath,
                pageNumber,
                null,
                meta.sourceUrl(),
                chunkType,
                index,
                breadcrumb + text
        );
    }

    private ChunkType classify(List<HeadingEntry> headingStack) {
        // Keyword list is intentionally bilingual: source documents are in both Swedish
        // ("grepp", "förkortning", "ordlista") and English ("abbreviation", "glossary"),
        // and headings must be matched in whichever language the source document uses.
        for (HeadingEntry entry : headingStack) {
            String lower = entry.title().toLowerCase();
            if (lower.contains("definition") || lower.contains("grepp") || lower.contains("förkortning")
                    || lower.contains("abbreviat") || lower.contains("glossary") || lower.contains("ordlista")) {
                return ChunkType.GLOSSARY;
            }
        }
        return ChunkType.TEXT;
    }

    private String buildSectionPath(List<HeadingEntry> headingStack) {
        if (headingStack.isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (HeadingEntry entry : headingStack) {
            if (!path.isEmpty()) {
                path.append(" > ");
            }
            path.append(entry.number() != null ? entry.number() + " " + entry.title() : entry.title());
        }
        return path.toString();
    }

    private void updateHeadingStack(List<HeadingEntry> headingStack, Heading heading) {
        int level = heading.getLevel();
        headingStack.removeIf(entry -> entry.level() >= level);
        String rawText = heading.getText().toString().strip();
        Matcher matcher = SECTION_NUMBER.matcher(rawText);
        if (matcher.matches()) {
            headingStack.add(new HeadingEntry(level, matcher.group(1), matcher.group(2).strip()));
        } else {
            headingStack.add(new HeadingEntry(level, null, rawText));
        }
    }

    /**
     * Splits the raw document text (after front matter removal) into segments at each
     * {@code <!-- page:N --> } marker, tagging every segment with the page number that was
     * active while it was authored. The marker text itself is discarded (never fed to the
     * Markdown parser or included in any chunk's text).
     */
    private List<Segment> splitByPageMarkers(String text) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = PAGE_MARKER.matcher(text);
        int previousEnd = 0;
        Integer currentPage = null;
        while (matcher.find()) {
            String before = text.substring(previousEnd, matcher.start());
            if (!before.isBlank()) {
                segments.add(new Segment(currentPage, before));
            }
            currentPage = Integer.valueOf(matcher.group(1));
            previousEnd = matcher.end();
        }
        String tail = text.substring(previousEnd);
        if (!tail.isBlank()) {
            segments.add(new Segment(currentPage, tail));
        }
        if (segments.isEmpty()) {
            segments.add(new Segment(null, text));
        }
        return segments;
    }

    /**
     * Splits an over-long section's accumulated text into multiple chunks at paragraph
     * boundaries, staying under {@link #MAX_CHUNK_CHARS}. Most sections are short enough that
     * this returns a single-element list.
     */
    private List<String> splitToSizeBudget(String content) {
        if (content.length() <= MAX_CHUNK_CHARS) {
            return List.of(content);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.split("\n\n")) {
            if (current.length() + paragraph.length() > MAX_CHUNK_CHARS && !current.isEmpty()) {
                parts.add(current.toString().strip());
                current = new StringBuilder();
            }
            current.append(paragraph).append("\n\n");
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().strip());
        }
        return parts;
    }

    private record Segment(Integer pageNumber, String text) {
    }

    private record HeadingEntry(int level, String number, String title) {
    }
}
