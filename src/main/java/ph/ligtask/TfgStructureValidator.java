package ph.ligtask;

import com.fasterxml.jackson.databind.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

/** Evaluates the keywords used by our bundled, closed upload schema. No remote schemas are loaded. */
@Component
public class TfgStructureValidator {
    private final JsonNode schema;
    private static final int MAX_ERRORS = 100;

    public TfgStructureValidator() throws IOException {
        try (var stream = new ClassPathResource("static/schemas/tfg-en-fil-v1.schema.json").getInputStream()) {
            schema = new ObjectMapper().readTree(stream);
        }
    }

    public List<String> validate(JsonNode document) {
        var errors = new ArrayList<String>();
        check(document, schema, "$", errors);
        if (errors.size() == MAX_ERRORS) errors.add("Showing the first 100 structure errors. Fix these and upload again.");
        return errors;
    }

    private void check(JsonNode value, JsonNode rule, String path, List<String> errors) {
        if (errors.size() >= MAX_ERRORS) return;
        if (rule.has("$ref")) { check(value, schema.at(rule.get("$ref").asText().substring(1)), path, errors); return; }
        if (rule.has("anyOf")) {
            List<String> closest = null;
            boolean typedOption = false;
            for (JsonNode option : rule.get("anyOf"))
                if (value != null && option.has("type") && matches(value, option.get("type"))) typedOption = true;
            for (JsonNode option : rule.get("anyOf")) {
                if (typedOption && option.has("type") && !matches(value, option.get("type"))) continue;
                var candidate = new ArrayList<String>();
                check(value, option, path, candidate);
                if (candidate.isEmpty()) return;
                if (closest == null || candidate.size() < closest.size()) closest = candidate;
            }
            for (String error : closest) add(errors, error);
            return;
        }
        if (value == null || (rule.has("type") && !matches(value, rule.get("type")))) {
            add(errors, path + ": expected " + rule.path("type") + ", found " + type(value) + "."); return;
        }
        if (rule.has("enum")) {
            boolean allowed = false;
            for (JsonNode option : rule.get("enum")) if (option.equals(value)) allowed = true;
            if (!allowed) add(errors, path + ": must be one of " + rule.get("enum") + ".");
        }
        if (value.isTextual() && rule.has("minLength") && value.asText().isBlank())
            add(errors, path + ": must be nonblank text.");
        if (value.isObject()) {
            for (JsonNode key : rule.path("required")) if (!value.has(key.asText()))
                add(errors, path + "." + key.asText() + ": required field is missing.");
            JsonNode properties = rule.path("properties");
            value.fields().forEachRemaining(field -> {
                if (properties.has(field.getKey())) check(field.getValue(), properties.get(field.getKey()), path + "." + field.getKey(), errors);
                else if (rule.has("additionalProperties") && !rule.get("additionalProperties").asBoolean())
                    add(errors, path + "." + field.getKey() + ": unexpected field; not part of the LIGTask TFG structure.");
            });
        }
        if (value.isArray()) {
            if (rule.has("minItems") && value.size() < rule.get("minItems").asInt()) add(errors, path + ": array must contain at least " + rule.get("minItems") + " item(s).");
            if (rule.has("maxItems") && value.size() > rule.get("maxItems").asInt()) {
                add(errors, path + ": array exceeds the " + rule.get("maxItems") + " entry limit."); return;
            }
            if (rule.has("items")) for (int i = 0; i < value.size() && errors.size() < MAX_ERRORS; i++)
                check(value.get(i), rule.get("items"), path + "[" + i + "]", errors);
        }
    }

    private void add(List<String> errors, String message) { if (errors.size() < MAX_ERRORS) errors.add(message); }
    private boolean matches(JsonNode value, JsonNode type) {
        if (type.isArray()) { for (JsonNode option : type) if (matches(value, option)) return true; return false; }
        return switch (type.asText()) {
            case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isTextual(); case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber(); case "number" -> value.isNumber();
            case "null" -> value.isNull(); default -> throw new IllegalStateException("Unsupported schema type");
        };
    }
    private String type(JsonNode value) { return value == null ? "missing" : value.getNodeType().name().toLowerCase(Locale.ROOT); }
}
