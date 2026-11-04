package ph.ligtask.llm;

import java.util.UUID;
import org.springframework.stereotype.Service;

/** Validated evaluation boundary. No automatic retries or result persistence. */
@Service
public class LigtaskEvaluationService {
    private final LigtaskPromptBuilderService builder;
    private final LlmProviderService providers;
    public LigtaskEvaluationService(LigtaskPromptBuilderService builder, LlmProviderService providers) {
        this.builder = builder;
        this.providers = providers;
    }
    public record Outcome(String modelKey, PreparedEvaluationInput input, LlmResponse rawResponse,
                          LigtaskOutputContract.Judgment judgment, String validationError) {
        public boolean valid() { return judgment != null && validationError == null; }
    }

    public Outcome evaluate(UUID datasetId, String instanceId, String modelKey, double temperature, int maxTokens) {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2 || maxTokens < 1)
            throw new IllegalArgumentException("Invalid temperature or maximum token count.");
        // These failures occur before any provider request.
        if (providers.getAvailableModels().stream().noneMatch(m -> m.key().equals(modelKey)))
            throw new IllegalArgumentException("Unknown model: " + modelKey);
        var input = builder.prepareInput(datasetId, instanceId);
        return evaluatePrepared(input, modelKey, temperature, maxTokens);
    }

    /** Executes an immutable saved snapshot, never silently re-reading a changed input. */
    public Outcome evaluatePrepared(PreparedEvaluationInput input, String modelKey, double temperature, int maxTokens) {
        if (input == null || !LigtaskOutputContract.VERSION.equals(input.contractVersion()))
            throw new IllegalArgumentException("Unsupported input contract.");
        try {
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!hash.equals(input.promptSha256())) throw new IllegalArgumentException("Input snapshot checksum mismatch.");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2 || maxTokens < 1)
            throw new IllegalArgumentException("Invalid evaluation settings.");
        // Provider errors propagate separately; they must not become Refuse judgments.
        var raw = providers.generate(modelKey, input.prompt(), temperature, maxTokens,
            LigtaskOutputContract.schema(input.taskType()));
        try {
            if (raw == null) throw new IllegalArgumentException("Provider returned no response.");
            if (!"stop".equals(raw.finishReason()))
                throw new IllegalArgumentException("Completion did not finish normally: " + raw.finishReason());
            var judgment = LigtaskOutputContract.validate(input.taskType(), raw.responseText());
            return new Outcome(modelKey, input, raw, judgment, null);
        } catch (IllegalArgumentException e) {
            // Retain invalid raw responses for review without silently repairing or retrying them.
            return new Outcome(modelKey, input, raw, null, e.getMessage());
        }
    }
}
