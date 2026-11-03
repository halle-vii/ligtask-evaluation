
package ph.ligtask.llm;

import java.util.Map;
import java.util.UUID;

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

        Map<String, Object> record = datasetStore.records(datasetId)
            .stream()
            .filter(row -> instanceId.equals(
                String.valueOf(row.get("instance_id"))
            ))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Dataset instance not found: " + instanceId
            ));

        String query = required(record, "prepared_prompt_text");
        String language = required(record, "language_variant");
        String context = optional(record, "context_text");
        String rawRecordText = required(record, "raw_record");

        try {
            JsonNode rawRecord = mapper.readTree(rawRecordText);

            String instruction =
                rawRecord.path("task_instruction").asText();

            JsonNode schemaNode = rawRecord.path("output_schema");

            if (instruction.isBlank()) {
                throw new IllegalArgumentException(
                    "Missing task_instruction in dataset record."
                );
            }

            if (schemaNode.isMissingNode()
                    || schemaNode.isNull()
                    || schemaNode.isEmpty()) {
                throw new IllegalArgumentException(
                    "Missing output_schema in dataset record."
                );
            }

            String outputSchema = schemaNode.toPrettyString();

            return promptTemplate.buildPrompt(
                instruction,
                query,
                language,
                context,
                outputSchema
            );

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Unable to read the dataset record JSON.", e
            );
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
