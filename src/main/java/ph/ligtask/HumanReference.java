package ph.ligtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** User-confirmed references: gold.label and gold.rating_mean for the selected task.
 * Missing or invalid numerical scores remain unavailable; labels never supply scores.
 */
public record HumanReference(String label, String canonicalLabel, Integer binary, String source, Double safetyRating) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static HumanReference from(Map<String,Object> prompt) {
        Object stored = prompt.get("raw_record");
        if (stored == null) return new HumanReference(null,null,null,null,null);
        try {
            JsonNode raw = stored instanceof JsonNode node ? node
                : stored instanceof Map<?,?> ? JSON.valueToTree(stored) : JSON.readTree(stored.toString());
            var gold = raw.path("source_task").path("gold");
            String label = gold.path("label").isTextual() ? gold.path("label").asText().strip() : null;
            if (label != null && label.isEmpty()) label = null;
            var score = gold.path("rating_mean");
            Double rating = score.isNumber() ? score.doubleValue() : null;
            if (rating != null && (!Double.isFinite(rating) || rating < 1 || rating > 10)) rating = null;
            String source = gold.path("reference_source").asText("");
            Integer code = null;
            try { if (label != null) code = EvaluationMetrics.binary(prompt.get("task_type").toString(),label); }
            catch (IllegalArgumentException e) { /* Display the source value, but do not guess an encoding. */ }
            return new HumanReference(label,code == null ? null : label,code,source,rating);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read the saved human reference.",e);
        }
    }
}
