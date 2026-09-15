package se.sveki.regulationsagent.ingestion;

import java.util.List;

/**
 * Document-level metadata parsed from the YAML front matter of a converted
 * {@code docs/sources/*.md} file. Front matter is deliberately kept flat and simple
 * (no nested structures beyond a single string list) so it can be parsed without
 * pulling in a full YAML library.
 */
public record DocumentFrontMatter(
        String documentId,
        String title,
        String federation,
        List<String> danceTypes,
        String version,
        String effectiveDate,
        String sourceType,
        String sourceLanguage,
        String sourceFile,
        String sourceUrl
) {
}
