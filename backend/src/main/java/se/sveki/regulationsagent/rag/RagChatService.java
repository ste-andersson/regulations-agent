package se.sveki.regulationsagent.rag;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
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
    private final String systemPromptTemplate;
    private final Map<String, ChatMemory> conversationMemories = new ConcurrentHashMap<>();

    public RagChatService(RegulationContentRetriever contentRetriever,
                           QueryExpansionService queryExpansionService,
                           ChatModel chatModel) {
        this.contentRetriever = contentRetriever;
        this.queryExpansionService = queryExpansionService;
        this.chatModel = chatModel;
        this.systemPromptTemplate = loadSystemPrompt();
    }

    public RagChatResult chat(@Nullable String conversationId, String message, @Nullable List<String> documentFilter) {
        if (!contentRetriever.isAvailable()) {
            throw new IllegalStateException(
                    "Astra DB is not configured (ASTRA_DB_APPLICATION_TOKEN / ASTRA_DB_API_ENDPOINT missing) - "
                            + "RAG chat is unavailable.");
        }
        String id = (conversationId == null || conversationId.isBlank()) ? UUID.randomUUID().toString() : conversationId;
        ChatMemory memory = conversationMemories.computeIfAbsent(id, key -> MessageWindowChatMemory.withMaxMessages(10));

        String expandedQuery = queryExpansionService.expandForSearch(message);
        List<Content> retrieved = contentRetriever.retrieve(expandedQuery, documentFilter);

        String systemPrompt = systemPromptTemplate + "\n\nCONTEXT:\n" + formatContext(retrieved);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(memory.messages());
        messages.add(UserMessage.from(message));

        ChatResponse response = chatModel.chat(messages);
        String answer = response.aiMessage().text();

        memory.add(UserMessage.from(message));
        memory.add(AiMessage.from(answer));

        return new RagChatResult(id, answer, buildCitations(retrieved));
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
