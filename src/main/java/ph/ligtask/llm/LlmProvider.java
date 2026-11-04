
package ph.ligtask.llm;

public interface LlmProvider {

    ModelConfiguration.Provider provider();

    LlmResponse generate(LlmRequest request);

    default String redactError(String text) { return text; }

    default boolean configured() { return true; }
}
