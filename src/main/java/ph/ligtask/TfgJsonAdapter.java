package ph.ligtask;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** TFG source array -> application records. Source gold is retained, never used as human labels. */
@Component
public class TfgJsonAdapter {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProvisionalJsonAdapter validator;
    public TfgJsonAdapter(ProvisionalJsonAdapter validator) { this.validator = validator; }

    public DatasetDocument parse(JsonNode source, byte[] bytes) throws Exception {
        var errors = new ArrayList<String>();
        ObjectNode normalized = mapper.createObjectNode();
        String version = "sha256-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        normalized.put("name", "LIGTask TFG English / Filipino").put("version", version)
            .put("schema_version", "provisional-v1").put("synthetic", false);
        ArrayNode records = normalized.putArray("records");
        if (source.isEmpty() || source.size() > 3333) errors.add("TFG: expected 1–3,333 source entries (up to 9,999 task instances).");
        else for (int i = 0; i < source.size(); i++) {
            JsonNode entry = source.get(i);
            String at = "Source entry " + (i + 1);
            if (!entry.isObject()) { errors.add(at + ": must be an object."); continue; }
            String base = text(entry, "base_id", at, errors);
            text(entry, "query", at, errors);
            String category = text(entry, "category", at, errors);
            String language = text(entry, "language", at, errors);
            if (!Set.of("en", "fil").contains(language)) errors.add(at + ": language must be en or fil.");
            JsonNode condition = entry.path("context_intended_to_be_safe");
            if (!condition.isBoolean()) errors.add(at + ": context_intended_to_be_safe must be a boolean; it cannot be inferred.");
            String context = condition.isBoolean() ? (condition.asBoolean() ? "SAFE" : "UNSAFE") : "UNKNOWN";
            JsonNode contextNode = entry.path("context");
            if (!contextNode.isMissingNode() && !contextNode.isNull() && !contextNode.isObject())
                errors.add(at + ": context must be an object or null.");
            JsonNode tasks = entry.path("task_derivatives");
            if (!tasks.isObject()) errors.add(at + ": task_derivatives must contain nlu, nlr, and nlg objects.");
            if (tasks.isObject()) tasks.fieldNames().forEachRemaining(k -> {
                if (!Set.of("nlu", "nlr", "nlg").contains(k)) errors.add(at + ": unsupported task key " + k + ".");
            });
            for (String task : List.of("nlu", "nlr", "nlg")) {
                String where = at + " / " + task;
                JsonNode derivative = tasks.path(task);
                if (!derivative.isObject()) { errors.add(where + ": task object is required."); continue; }
                String code = text(derivative, "task_code", where, errors);
                if (!code.equals(task.toUpperCase(Locale.ROOT))) errors.add(where + ": task_code must match " + task.toUpperCase(Locale.ROOT) + ".");
                String instruction = text(derivative, "instruction", where, errors);
                if (!derivative.path("output_schema").isObject() || derivative.path("output_schema").isEmpty())
                    errors.add(where + ": output_schema must be a nonempty object.");
                JsonNode input = derivative.path("input");
                if (!input.isObject()) errors.add(where + ": input must be an object.");
                String query = text(input, "query", where + " / input", errors);
                for (String field : List.of("base_id", "language", "category", "context")) {
                    if (!Objects.equals(entry.get(field), input.get(field)))
                        errors.add(where + ": input." + field + " must match the source entry.");
                }
                ObjectNode record = records.addObject();
                String taskId = base + "_" + context + "_" + code;
                record.put("instance_id", taskId + "_" + language.toUpperCase(Locale.ROOT))
                    .put("upstream_entry_id", taskId).put("base_query_id", base)
                    .put("context_id", base + "_" + context).put("context_condition", context)
                    .put("task_type", code).put("language_variant", language.toUpperCase(Locale.ROOT))
                    .put("prepared_prompt_text", query).put("category", category)
                    .put("task_instruction", instruction).putNull("policy_text");
                if (contextNode.isObject() && !contextNode.isEmpty()) {
                    record.put("context_text", contextNode.toPrettyString());
                    flatten("", contextNode, record.putObject("context_attributes"));
                } else record.putNull("context_text");
                record.set("output_schema", derivative.path("output_schema"));
                // Retain every source field, including unconfirmed gold, without mixing it into the query.
                ObjectNode sourceEntry = ((ObjectNode) entry).deepCopy();
                sourceEntry.remove("task_derivatives");
                record.set("source_entry", sourceEntry);
                record.set("source_task", derivative);
                record.putObject("import_metadata").put("adapter", "tfg-en-fil-v1")
                    .put("source_entry_index", i).put("task_key", task)
                    .put("identifier_origin", "Derived from base_id, explicit context condition, task_code and language; no source instance IDs supplied");
            }
        }
        DatasetDocument validated = validator.parse(normalized.toString().getBytes(StandardCharsets.UTF_8));
        errors.addAll(validated.errors());
        return new DatasetDocument(validated.name(), version, "tfg-en-fil-v1", false, source,
            validated.records(), errors, validated.warnings());
    }

    private String text(JsonNode node, String key, String at, List<String> errors) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) {
            errors.add(at + ": " + key + " is required text."); return "";
        }
        return value.asText();
    }

    private void flatten(String path, JsonNode value, ObjectNode attributes) {
        if (value.isObject() && !value.isEmpty()) value.fields().forEachRemaining(e ->
            flatten(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), e.getValue(), attributes));
        else attributes.put(path, value.isTextual() ? value.asText() : value.toString());
    }
}
