
package ph.ligtask.llm;

public record LlmRequest(
    String modelId,
    String prompt,
    double temperature,
    int maxTokens
) {}
