
package ph.ligtask.llm;

import java.util.List;

public final class ModelConfiguration {

    private ModelConfiguration() {}

    public enum Provider {
        OPENROUTER,
        HUGGINGFACE
    }

    public record Model(
        String key,
        String displayName,
        Provider provider,
        String modelId
    ) {}

    public static final List<Model> MODELS = List.of(
        new Model(
            "gpt-4o",
            "GPT-4o",
            Provider.OPENROUTER,
            "openai/gpt-4o-2024-08-06"
        ),
        new Model(
            "gpt-4o-mini",
            "GPT-4o-mini",
            Provider.OPENROUTER,
            "openai/gpt-4o-mini-2024-07-18"
        ),
        new Model(
            "claude-sonnet-4-5",
            "Claude Sonnet 4.5",
            Provider.OPENROUTER,
            "anthropic/claude-sonnet-4.5"
        ),
        new Model(
            "llama-3-70b-instruct",
            "Llama-3-70B-Instruct",
            Provider.HUGGINGFACE,
            "meta-llama/Meta-Llama-3-70B-Instruct"
        ),
        new Model(
            "qwen2-72b",
            "Qwen2-72B",
            Provider.HUGGINGFACE,
            "Qwen/Qwen2-72B-Instruct"
        ),
        new Model(
            "dolphin-2-9-llama3-70b",
            "Dolphin-2.9-Llama3-70B",
            Provider.HUGGINGFACE,
            "dphn/dolphin-2.9-llama3-70b"
        ),
        new Model(
            "mixtral-8x7b",
            "Mixtral 8x7B",
            Provider.HUGGINGFACE,
            "mistralai/Mixtral-8x7B-Instruct-v0.1"
        )
    );
}
