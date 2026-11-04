package ph.ligtask.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record LlmRequest(
    String modelId,
    String prompt,
    double temperature,
    int maxTokens,
    JsonNode outputSchema
) {
    /** Connectivity checks remain plain-text requests. */
    public LlmRequest(String modelId, String prompt, double temperature, int maxTokens) {
        this(modelId, prompt, temperature, maxTokens, null);
    }

    public LlmRequest {
        if (outputSchema != null) {
            if (!outputSchema.isObject() || outputSchema.isEmpty()) {
                throw new IllegalArgumentException("Output schema must be a nonempty object.");
            }
            outputSchema = outputSchema.deepCopy();
        }
    }

    @Override public JsonNode outputSchema() {
        return outputSchema == null ? null : outputSchema.deepCopy();
    }
}
