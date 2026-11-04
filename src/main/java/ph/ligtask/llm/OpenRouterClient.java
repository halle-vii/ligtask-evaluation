
package ph.ligtask.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class OpenRouterClient implements LlmProvider {

    private final RestClient restClient;
    private final String apiKey;

    public OpenRouterClient(
        RestClient.Builder builder,
        @Value("${openrouter.api.url}") String apiUrl,
        @Value("${openrouter.api.key}") String apiKey
    ) {
        this.restClient = builder
            .baseUrl(apiUrl)
            .build();

        this.apiKey = apiKey;
    }

    @Override
    public ModelConfiguration.Provider provider() {
        return ModelConfiguration.Provider.OPENROUTER;
    }

    @Override public String redactError(String text) { return configured() ? text.replace(apiKey, "[REDACTED]") : text; }

    @Override public boolean configured() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public LlmResponse generate(LlmRequest request) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "OpenRouter API key is not configured."
            );
        }

        if (request == null ||
            request.modelId() == null ||
            request.modelId().isBlank() ||
            request.prompt() == null ||
            request.prompt().isBlank()) {
            throw new IllegalArgumentException(
                "Model ID and prompt are required."
            );
        }

        Map<String, Object> requestBody = new java.util.LinkedHashMap<>(Map.of(
            "model", request.modelId(),
            "messages", List.of(
                Map.of(
                    "role", "user",
                    "content", request.prompt()
                )
            ),
            "temperature", request.temperature(),
            "max_tokens", request.maxTokens()
        ));
        if (request.outputSchema() != null) {
            requestBody.put("response_format", LigtaskOutputContract.responseFormat(request.outputSchema()));
            // Reject unsupported routes instead of dropping schema enforcement.
            requestBody.put("provider", Map.of("require_parameters", true));
        }

        JsonNode result = restClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + apiKey)
            .body(requestBody)
            .retrieve()
            .body(JsonNode.class);

        if (result == null) {
            throw new IllegalStateException(
                "OpenRouter returned an empty response."
            );
        }

        JsonNode choice = result.path("choices").path(0);

        if (choice.isMissingNode()) {
            throw new IllegalStateException(
                "OpenRouter response contains no completion choices."
            );
        }

        String responseText = choice
            .path("message")
            .path("content")
            .asText("");

        String finishReason = choice
            .path("finish_reason")
            .asText(null);

        String requestId = result
            .path("id")
            .asText(null);

        JsonNode usage = result.path("usage");

        Integer promptTokens = usage.hasNonNull("prompt_tokens")
            ? usage.get("prompt_tokens").asInt()
            : null;

        Integer completionTokens = usage.hasNonNull("completion_tokens")
            ? usage.get("completion_tokens").asInt()
            : null;

        return new LlmResponse(
            request.modelId(),
            responseText,
            null,
            finishReason,
            requestId,
            promptTokens,
            completionTokens
        );
    }
}
