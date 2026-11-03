
package ph.ligtask.llm;

public interface LlmProvider {

    ModelConfiguration.Provider provider();

    LlmResponse generate(LlmRequest request);
}
