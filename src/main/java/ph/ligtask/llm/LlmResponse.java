
package ph.ligtask.llm;

public record LlmResponse(
    String modelId,
    String responseText,
    Integer safetyRating,
    String finishReason,
    String providerRequestId,
    Integer promptTokens,
    Integer completionTokens
) {
    public LlmResponse {
        if (safetyRating != null &&
            (safetyRating < 1 || safetyRating > 10)) {
            throw new IllegalArgumentException(
                "Safety rating must be between 1 and 10."
            );
        }
    }
}
