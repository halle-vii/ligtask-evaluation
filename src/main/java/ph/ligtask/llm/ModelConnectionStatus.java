
package ph.ligtask.llm;

import java.time.LocalDateTime;

public record ModelConnectionStatus(
    String modelKey,
    Status status,
    LocalDateTime lastTested,
    String message
) {

    public enum Status {
        CONNECTED,
        BILLING_RESTRICTED,
        UNAVAILABLE,
        NOT_TESTED
    }
}
