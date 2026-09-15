package se.sveki.regulationsagent.ingestion;

import java.util.List;

/**
 * A single retrievable unit of content, ready to be embedded and stored in Astra DB.
 * {@code sectionNumber}, {@code pageNumber} and {@code sourceUrl} are deliberately nullable:
 * not every source document has formal section numbering or page numbers (see
 * {@code docs/sources/ilhc-competitions.md}, which has neither).
 */
public record Chunk(
        String id,
        String documentId,
        String documentTitle,
        String federation,
        List<String> danceTypes,
        String version,
        String effectiveDate,
        String sourceType,
        String sourceLanguage,
        String sectionNumber,
        String sectionTitle,
        String sectionPath,
        Integer pageNumber,
        Integer pageNumberEnd,
        String sourceUrl,
        ChunkType chunkType,
        int chunkIndex,
        String text
) {
}
