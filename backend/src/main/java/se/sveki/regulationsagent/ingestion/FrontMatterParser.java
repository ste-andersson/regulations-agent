package se.sveki.regulationsagent.ingestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the flat YAML front matter used at the top of every {@code docs/sources/*.md} file
 * (between the first two {@code ---} lines). This is intentionally not a general YAML parser:
 * it only supports {@code key: value} pairs and single-line {@code key: ["a", "b"]} string lists,
 * which is all the front matter in this project ever uses.
 */
public final class FrontMatterParser {

    private static final Pattern LIST_ITEM = Pattern.compile("\"([^\"]*)\"");

    private FrontMatterParser() {
    }

    /**
     * @return a pair of (frontMatter, remainingBody). If the text has no front matter,
     * an empty frontmatter map and the original text are returned.
     */
    public static Result parse(String rawMarkdown) {
        String text = rawMarkdown.stripLeading();
        if (!text.startsWith("---")) {
            return new Result(Map.of(), rawMarkdown);
        }
        int firstEnd = text.indexOf('\n');
        if (firstEnd < 0) {
            return new Result(Map.of(), rawMarkdown);
        }
        int closingDashIndex = text.indexOf("\n---", firstEnd);
        if (closingDashIndex < 0) {
            return new Result(Map.of(), rawMarkdown);
        }
        String frontMatterBlock = text.substring(firstEnd + 1, closingDashIndex);
        int bodyStart = text.indexOf('\n', closingDashIndex + 1);
        String body = bodyStart >= 0 ? text.substring(bodyStart + 1) : "";

        Map<String, Object> values = new LinkedHashMap<>();
        for (String line : frontMatterBlock.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String rawValue = line.substring(colon + 1).trim();
            values.put(key, parseValue(rawValue));
        }
        return new Result(values, body);
    }

    private static Object parseValue(String rawValue) {
        if (rawValue.isEmpty() || rawValue.equals("null")) {
            return null;
        }
        if (rawValue.startsWith("[") && rawValue.endsWith("]")) {
            List<String> items = new ArrayList<>();
            Matcher matcher = LIST_ITEM.matcher(rawValue);
            while (matcher.find()) {
                items.add(matcher.group(1));
            }
            return items;
        }
        if ((rawValue.startsWith("\"") && rawValue.endsWith("\"")) && rawValue.length() >= 2) {
            return rawValue.substring(1, rawValue.length() - 1);
        }
        return rawValue;
    }

    public record Result(Map<String, Object> frontMatter, String body) {

        public String getString(String key) {
            Object value = frontMatter.get(key);
            return value == null ? null : value.toString();
        }

        @SuppressWarnings("unchecked")
        public List<String> getList(String key) {
            Object value = frontMatter.get(key);
            if (value instanceof List<?> list) {
                return (List<String>) list;
            }
            return List.of();
        }

        public DocumentFrontMatter toDocumentFrontMatter() {
            return new DocumentFrontMatter(
                    getString("document_id"),
                    getString("title"),
                    getString("federation"),
                    getList("dance_types"),
                    getString("version"),
                    getString("effective_date"),
                    getString("source_type"),
                    getString("source_language"),
                    getString("source_file"),
                    getString("source_url")
            );
        }
    }
}
