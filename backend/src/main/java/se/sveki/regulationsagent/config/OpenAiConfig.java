package se.sveki.regulationsagent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI is used for both chat completions and embeddings (see docs/plan.md, "Beslut som styr
 * planen"). Builders here never make network calls, so these beans are safe to construct even
 * when {@code OPENAI_API_KEY} is not yet configured locally — any actual call will fail with a
 * clear authentication error at that point instead of preventing the whole app from starting.
 */
@Configuration
public class OpenAiConfig {

    @Bean
    public ChatModel chatModel(AppProperties props) {
        return OpenAiChatModel.builder()
                .apiKey(blankToPlaceholder(props.openai().apiKey()))
                .modelName(props.openai().chatModel())
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(AppProperties props) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(blankToPlaceholder(props.openai().apiKey()))
                .modelName(props.openai().embeddingModel())
                .build();
    }

    private String blankToPlaceholder(String apiKey) {
        return (apiKey == null || apiKey.isBlank()) ? "not-configured" : apiKey;
    }
}
