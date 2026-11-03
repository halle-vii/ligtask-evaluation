
package ph.ligtask.llm;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmProviderService {

    private final Map<ModelConfiguration.Provider, LlmProvider> providers;

    public LlmProviderService(List<LlmProvider> providerList) {

        this.providers = new EnumMap<>(ModelConfiguration.Provider.class);

        for (LlmProvider provider : providerList) {

            LlmProvider existing = providers.put(
                provider.provider(),
                provider
            );

            if (existing != null) {
                throw new IllegalStateException(
                    "Duplicate LLM provider configuration: "
                    + provider.provider()
                );
            }
        }
    }

    public LlmResponse generate(
        String modelKey,
        String prompt,
        double temperature,
        int maxTokens
    ) {

        ModelConfiguration.Model model = ModelConfiguration.MODELS
            .stream()
            .filter(candidate -> candidate.key().equals(modelKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown model: " + modelKey
            ));

        LlmProvider provider = providers.get(model.provider());

        if (provider == null) {
            throw new IllegalStateException(
                "No provider configured for: " + model.provider()
            );
        }

        LlmRequest request = new LlmRequest(
            model.modelId(),
            prompt,
            temperature,
            maxTokens
        );

        return provider.generate(request);
    }

    public List<ModelConfiguration.Model> getAvailableModels() {
        return ModelConfiguration.MODELS;
    }
}
