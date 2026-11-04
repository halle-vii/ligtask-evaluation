
package ph.ligtask.llm;

import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ph.ligtask.DatasetStore;

@Service
public class LigtaskPromptBuilderService {

    private final DatasetStore datasetStore;
    private final LigtaskPromptTemplate promptTemplate;
    private final ObjectMapper mapper;

    public LigtaskPromptBuilderService(
        DatasetStore datasetStore,
        LigtaskPromptTemplate promptTemplate
    ) {
        this.datasetStore = datasetStore;
        this.promptTemplate = promptTemplate;
        this.mapper = new ObjectMapper();
    }

    public String buildPrompt(UUID datasetId, String instanceId) {
        return prepareInput(datasetId, instanceId).prompt();
    }

    public PreparedEvaluationInput prepareInput(UUID datasetId, String instanceId) {

        // 1. Retrieve the selected dataset record
        Map<String, Object> record = datasetStore.records(datasetId)
            .stream()
            .filter(row -> instanceId.equals(
                String.valueOf(row.get("instance_id"))
            ))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Dataset instance not found: " + instanceId
            ));

        return prepareRecord(datasetId, record);
    }

    public PreparedEvaluationInput prepareRecord(UUID datasetId, Map<String, Object> record) {
        String instanceId = required(record, "instance_id");
        // 2. Retrieve evaluation information
        String taskType = required(record, "task_type").toUpperCase(Locale.ROOT);
        String query = required(record, "prepared_prompt_text");
        String language = required(record, "language_variant");
        if (!java.util.Set.of("EN", "FIL").contains(language)) {
            throw new IllegalArgumentException("Unsupported language: " + language);
        }
        String context = optional(record, "context_text");
        String policy = optional(record, "policy_text");
        String rawRecordText = required(record, "raw_record");

        // 3. Validate task type
        if (!taskType.equals("NLU")
                && !taskType.equals("NLR")
                && !taskType.equals("NLG")) {
            throw new IllegalArgumentException(
                "Unsupported task type: " + taskType
            );
        }

        try {

            // 4. Read task-specific instruction and output schema
            JsonNode rawRecord = mapper.readTree(rawRecordText);

            String instruction =
                rawRecord.path("task_instruction").asText();

            // Validate persisted columns against the immutable imported record.
            for (String field : java.util.List.of("instance_id", "prepared_prompt_text", "task_type", "language_variant")) {
                if (!required(record, field).equals(rawRecord.path(field).asText())) {
                    throw new IllegalArgumentException("Stored input mismatch: " + field);
                }
            }
            if (!java.util.Objects.equals(record.get("context_text"),
                    rawRecord.hasNonNull("context_text") ? rawRecord.get("context_text").asText() : null)
                || !java.util.Objects.equals(record.get("policy_text"),
                    rawRecord.hasNonNull("policy_text") ? rawRecord.get("policy_text").asText() : null)) {
                throw new IllegalArgumentException("Stored context or policy differs from the imported record.");
            }
            // Derive the current contract at runtime, including for older saved imports.
            JsonNode schemaNode = LigtaskOutputContract.schema(taskType);

            if (instruction.isBlank()) {
                throw new IllegalArgumentException(
                    "Missing task_instruction in dataset record."
                );
            }

            if (!schemaNode.isObject() || schemaNode.isEmpty()) {
                throw new IllegalArgumentException(
                    "Missing output_schema in dataset record."
                );
            }

            String outputSchema = schemaNode.toPrettyString();

            // 5. Generate the final evaluation prompt
            String prompt = promptTemplate.buildPrompt(
                taskType,
                instruction,
                query,
                language,
                context,
                policy,
                outputSchema
            );
            return new PreparedEvaluationInput(datasetId, instanceId, taskType, language,
                LigtaskOutputContract.VERSION, prompt, sha256(prompt));

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Unable to read the dataset record JSON.", e
            );
        }
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String required(
        Map<String, Object> record,
        String field
    ) {
        Object value = record.get(field);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(
                "Missing required dataset field: " + field
            );
        }

        return value.toString();
    }

    private String optional(
        Map<String, Object> record,
        String field
    ) {
        Object value = record.get(field);
        return value == null ? "" : value.toString();
    }
}
