package se.sveki.regulationsagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.Content;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import se.sveki.regulationsagent.config.AppProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates a single RAG chat turn: expand the query, retrieve context, build the prompt,
 * call the chat model, and build citations from the same retrieved chunks used for the prompt
 * (never by parsing the model's free-text answer) — see docs/plan.md, "RAG query flow".
 */
@Service
public class RagChatService {

    private static final int MAX_SNIPPET_CHARS = 280;

    private final RegulationContentRetriever contentRetriever;
    private final QueryExpansionService queryExpansionService;
    private final ChatModel chatModel;
    private final String chatModelName;
    private final String systemPromptTemplate;
    private final Map<String, ChatMemory> conversationMemories = new ConcurrentHashMap<>();
    @Nullable
    private final Tracer tracer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagChatService(RegulationContentRetriever contentRetriever,
                           QueryExpansionService queryExpansionService,
                           ChatModel chatModel,
                           AppProperties props,
                           @Nullable Tracer tracer) {
        this.contentRetriever = contentRetriever;
        this.queryExpansionService = queryExpansionService;
        this.chatModel = chatModel;
        this.chatModelName = props.openai().chatModel();
        this.systemPromptTemplate = loadSystemPrompt();
        this.tracer = tracer;
    }

    public RagChatResult chat(@Nullable String conversationId, String message, @Nullable List<String> documentFilter) {
        if (!contentRetriever.isAvailable()) {
            throw new IllegalStateException(
                    "Astra DB is not configured (ASTRA_DB_APPLICATION_TOKEN / ASTRA_DB_API_ENDPOINT missing) - "
                            + "RAG chat is unavailable.");
        }
        String id = (conversationId == null || conversationId.isBlank()) ? UUID.randomUUID().toString() : conversationId;
        ChatMemory memory = conversationMemories.computeIfAbsent(id, key -> MessageWindowChatMemory.withMaxMessages(10));

        Span trace = startSpan("rag-chat", null);
        setAttribute(trace, "langfuse.trace.name", "rag-chat");
        setAttribute(trace, "langfuse.session.id", id);
        setAttribute(trace, "langfuse.observation.input", toJson(message));
        try {
            String expandedQuery = queryExpansionService.expandForSearch(message);

            Span retrievalSpan = startSpan("retrieval", trace);
            setAttribute(retrievalSpan, "langfuse.observation.type", "retriever");
            setAttribute(retrievalSpan, "langfuse.observation.input", toJson(expandedQuery));
            List<Content> retrieved = contentRetriever.retrieve(expandedQuery, documentFilter);
            setAttribute(retrievalSpan, "langfuse.observation.output", toJson(retrievalSummary(retrieved)));
            endSpan(retrievalSpan);

            String systemPrompt = systemPromptTemplate + "\n\nCONTEXT:\n" + formatContext(retrieved);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt));
            messages.addAll(memory.messages());
            messages.add(UserMessage.from(message));

            Span generationSpan = startSpan("generation", trace);
            setAttribute(generationSpan, "langfuse.observation.type", "generation");
            setAttribute(generationSpan, "langfuse.observation.model.name", chatModelName);
            setAttribute(generationSpan, "gen_ai.request.model", chatModelName);
            setAttribute(generationSpan, "langfuse.observation.input", toJson(promptSummary(messages)));

            ChatResponse response = chatModel.chat(messages);
            String answer = response.aiMessage().text();

            setAttribute(generationSpan, "langfuse.observation.output", toJson(answer));
            TokenUsage tokenUsage = response.tokenUsage();
            if (tokenUsage != null) {
                if (tokenUsage.inputTokenCount() != null) {
                    setLongAttribute(generationSpan, "gen_ai.usage.input_tokens", tokenUsage.inputTokenCount());
                }
                if (tokenUsage.outputTokenCount() != null) {
                    setLongAttribute(generationSpan, "gen_ai.usage.output_tokens", tokenUsage.outputTokenCount());
                }
            }
            endSpan(generationSpan);

            memory.add(UserMessage.from(message));
            memory.add(AiMessage.from(answer));

            setAttribute(trace, "langfuse.observation.output", toJson(answer));
            return new RagChatResult(id, answer, buildCitations(retrieved));
        } finally {
            endSpan(trace);
        }
    }

    @Nullable
    private Span startSpan(String name, @Nullable Span parent) {
        if (tracer == null) {
            return null;
        }
        var builder = tracer.spanBuilder(name);
        if (parent != null) {
            builder.setParent(parent.storeInContext(Context.root()));
        }
        return builder.startSpan();
    }

    private void setAttribute(@Nullable Span span, String key, String value) {
        if (span != null) {
            span.setAttribute(key, value);
        }
    }

    private void setLongAttribute(@Nullable Span span, String key, long value) {
        if (span != null) {
            span.setAttribute(key, value);
        }
    }

    private void endSpan(@Nullable Span span) {
        if (span != null) {
            span.end();
        }
    }

    private String toJson(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            return "\"\"";
        }
    }

    private String retrievalSummary(List<Content> retrieved) {
        List<String> summary = new ArrayList<>();
        for (Content content : retrieved) {
            var metadata = content.textSegment().metadata();
            summary.add(metadata.getString("document_id") + "#"
                    + (metadata.getString("section_number") != null ? metadata.getString("section_number") : "?"));
        }
        return String.join(", ", summary);
    }

    private String promptSummary(List<ChatMessage> messages) {
        // The full system prompt (with the whole retrieved CONTEXT block) is long and would
        // duplicate what the "retrieval" span already records - the generation span's input just
        // needs to show what was actually asked, for readability in the Langfuse UI.
        ChatMessage last = messages.get(messages.size() - 1);
        return last instanceof UserMessage userMessage ? userMessage.singleText() : last.toString();
    }

    private List<Citation> buildCitations(List<Content> retrieved) {
        List<Citation> citations = new ArrayList<>();
        for (Content content : retrieved) {
            dev.langchain4j.data.document.Metadata metadata = content.textSegment().metadata();
            citations.add(new Citation(
                    metadata.getString("document_id"),
                    metadata.getString("document_title"),
                    metadata.getString("federation"),
                    metadata.getString("section_number"),
                    metadata.getString("section_title"),
                    metadata.getInteger("page_number"),
                    metadata.getString("source_url"),
                    snippet(content.textSegment().text())
            ));
        }
        return citations;
    }

    private String formatContext(List<Content> retrieved) {
        StringBuilder context = new StringBuilder();
        int index = 1;
        for (Content content : retrieved) {
            dev.langchain4j.data.document.Metadata metadata = content.textSegment().metadata();
            context.append("[Source ").append(index++).append("]\n");
            context.append("Document: ").append(metadata.getString("document_title"))
                    .append(" (").append(metadata.getString("document_id")).append(")");
            if (metadata.getString("federation") != null) {
                context.append(", ").append(metadata.getString("federation"));
            }
            context.append("\n");

            String sectionNumber = metadata.getString("section_number");
            String sectionTitle = metadata.getString("section_title");
            if (sectionNumber != null || sectionTitle != null) {
                context.append("Section: ");
                if (sectionNumber != null) {
                    context.append(sectionNumber).append(" ");
                }
                if (sectionTitle != null) {
                    context.append(sectionTitle);
                }
                context.append("\n");
            }
            Integer pageNumber = metadata.getInteger("page_number");
            if (pageNumber != null) {
                context.append("Page: ").append(pageNumber).append("\n");
            }
            if (metadata.getString("source_url") != null) {
                context.append("Source URL: ").append(metadata.getString("source_url")).append("\n");
            }
            context.append("Text:\n").append(content.textSegment().text()).append("\n\n");
        }
        return context.toString();
    }

    private String snippet(String text) {
        String stripped = text.strip();
        return stripped.length() <= MAX_SNIPPET_CHARS ? stripped : stripped.substring(0, MAX_SNIPPET_CHARS) + "...";
    }

    private String loadSystemPrompt() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("prompts/system-prompt.txt")) {
            if (in == null) {
                throw new IllegalStateException("prompts/system-prompt.txt not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record RagChatResult(String conversationId, String answer, List<Citation> citations) {
    }
}
