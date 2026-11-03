
package ph.ligtask.llm;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

@RestController
public class LlmTestController {

    private final LlmProviderService llmProviderService;
    private final ModelConnectionStatusService statusService;

    public LlmTestController(
        LlmProviderService llmProviderService,
        ModelConnectionStatusService statusService
    ) {
        this.llmProviderService = llmProviderService;
        this.statusService = statusService;
    }

    @GetMapping("/api/llm/test")
    public LlmResponse testConnection(
        @RequestParam String model
    ) {
        String testPrompt =
            "Reply with exactly: API connection successful.";

        try {
            LlmResponse response = llmProviderService.generate(
                model,
                testPrompt,
                0.0,
                50
            );

            statusService.updateStatus(
                model,
                ModelConnectionStatus.Status.CONNECTED,
                "API connection successful."
            );

            return response;

        } catch (RestClientResponseException e) {

            String errorBody = e.getResponseBodyAsString();
            String error = errorBody.toLowerCase();

            HttpStatusCode httpStatus = e.getStatusCode();

            if (error.contains("credit") ||
                error.contains("billing") ||
                error.contains("payment") ||
                error.contains("quota")) {

                statusService.updateStatus(
                    model,
                    ModelConnectionStatus.Status.BILLING_RESTRICTED,
                    "Provider reached, but billing or credits prevent generation."
                );

            } else if (error.contains("model_not_supported") ||
                       error.contains("not supported by any provider") ||
                       error.contains("model not found")) {

                statusService.updateStatus(
                    model,
                    ModelConnectionStatus.Status.UNAVAILABLE,
                    "The requested model is not supported by the available provider."
                );

            } else {
                statusService.updateStatus(
                    model,
                    ModelConnectionStatus.Status.UNAVAILABLE,
                    "API request failed with HTTP status " + httpStatus.value() + "."
                );
            }

            throw e;
        }
    }
}
