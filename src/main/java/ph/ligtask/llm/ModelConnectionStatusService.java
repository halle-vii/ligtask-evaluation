
package ph.ligtask.llm;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class ModelConnectionStatusService {

    private final Map<String, ModelConnectionStatus> statuses =
        new ConcurrentHashMap<>();

    public void updateStatus(
        String modelKey,
        ModelConnectionStatus.Status status,
        String message
    ) {
        statuses.put(
            modelKey,
            new ModelConnectionStatus(
                modelKey,
                status,
                LocalDateTime.now(),
                message
            )
        );
    }

    public ModelConnectionStatus getStatus(String modelKey) {
        return statuses.getOrDefault(
            modelKey,
            new ModelConnectionStatus(
                modelKey,
                ModelConnectionStatus.Status.NOT_TESTED,
                null,
                "No connection test has been recorded."
            )
        );
    }
}
