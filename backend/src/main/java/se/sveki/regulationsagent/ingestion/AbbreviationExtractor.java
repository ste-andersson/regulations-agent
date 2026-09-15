package se.sveki.regulationsagent.ingestion;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts an abbreviation -&gt; expansion dictionary from a document's glossary chunk(s)
 * (e.g. BRR's "RG = regelgrupp", "UK = Ungdomskampen" style definitions). Used at query time
 * for query expansion so retrieval works even when a question only uses the abbreviation.
 */
@Component
public class AbbreviationExtractor {

    private static final Pattern DEFINITION_LINE =
            Pattern.compile("^([A-ZÅÄÖ0-9]{2,8})\\s*=\\s*(.+)$");

    public Map<String, String> extract(List<Chunk> chunks) {
        Map<String, String> abbreviations = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            if (chunk.chunkType() != ChunkType.GLOSSARY) {
                continue;
            }
            for (String line : chunk.text().split("\n")) {
                Matcher matcher = DEFINITION_LINE.matcher(line.strip());
                if (matcher.matches()) {
                    abbreviations.put(matcher.group(1), matcher.group(2).strip());
                }
            }
        }
        return abbreviations;
    }
}
