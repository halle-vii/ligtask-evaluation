package ph.ligtask;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class TfgJsonAdapterTest {

    private final DatasetImporter importer;
    TfgJsonAdapterTest() throws Exception { importer = new DatasetImporter(new TfgStructureValidator(), new TfgJsonAdapter(new ProvisionalJsonAdapter())); }

    static ArrayNode fixture() {
        var mapper = new ObjectMapper();
        var array = mapper.createArrayNode();
        for (boolean safe : new boolean[]{true, false}) for (String language : new String[]{"en", "fil"}) {
            var entry = array.addObject().put("base_id", "SYN_TEST")
                .put("query", "SYNTHETIC source query").put("source_query_en", "Supplied original")
                .put("category", "Synthetic category").put("context_intended_to_be_safe", safe)
                .put("language", language).put("processing_stage", "Task Frame Generation");
            var context = entry.putObject("context");
            context.putObject("sender").put("nature_of_the_interaction", "Synthetic interaction").put("platform_type", "Synthetic platform");
            context.putObject("recipient").put("type", "Synthetic recipient").put("background", "Supplied context, unchanged");
            context.putObject("transmission_principle").put("sender_purpose", "Synthetic purpose")
                .put("confidentiality", "Synthetic confidentiality").put("source_accountability", "Synthetic accountability");
            var tasks = entry.putObject("task_derivatives");
            for (String task : new String[]{"nlu", "nlr", "nlg"}) {
                var derivative = tasks.putObject(task).put("task_code", task.toUpperCase())
                    .put("instruction", "SYNTHETIC instruction").put("task_name", "Synthetic task")
                    .put("capability", "Synthetic capability").put("research_role", "Synthetic role");
                var input = derivative.putObject("input").put("base_id", "SYN_TEST")
                    .put("language", language).put("category", "Synthetic category")
                    .put("query", language.equals("fil") ? "SYNTHETIC: Magbigay ng paanyaya." : "SYNTHETIC: Write an invitation.");
                input.set("context", entry.path("context").deepCopy());
                if (language.equals("fil")) input.put("source_query_en", "Supplied task source query");
                var output = derivative.putObject("output_schema");
                output.putArray("label").add("Synthetic label");
                switch (task) {
                    case "nlu" -> output.put("classification", "Synthetic category").put("classification_rationale", "Synthetic rationale").put("rationale", "Synthetic rationale");
                    case "nlr" -> {
                        output.put("claim", "Synthetic claim");
                        output.putArray("supporting_arguments").add("Synthetic supporting argument");
                        output.putArray("counterarguments").add("Synthetic counterargument");
                        output.putArray("relationships").addObject().put("source", "Synthetic source").put("target", "Synthetic target").putArray("relation").add("supports");
                    }
                    case "nlg" -> output.put("response", "Expected response shape").put("safety_rationale", "Synthetic rationale");
                }
                var gold = derivative.putObject("gold").put("binary_rating", safe ? 0 : 1).putNull("rating_mean").put("label", safe ? "Safe" : "Unsafe")
                    .put("reference_source", "Replace or confirm through human annotation.");
                if (!task.equals("nlu")) gold.put("source_label", safe ? "Safe" : "Unsafe");
            }
        }
        return array;
    }

    private DatasetDocument parse(JsonNode root) { return importer.parse(root.toString().getBytes(StandardCharsets.UTF_8)); }

    @Test void expandsExplicitFactorsAndPreservesSourceWithoutPromotingGold() {
        var source = fixture();
        var document = parse(source);
        assertThat(document.errors()).isEmpty();
        assertThat(document.records()).hasSize(12);
        assertThat(document.schemaVersion()).isEqualTo("tfg-en-fil-v1");
        assertThat(document.raw()).isEqualTo(source);
        assertThat(document.version()).isEqualTo(parse(source).version());
        assertThat(Coverage.of(document.records()).missingVariants()).isEmpty();
        for (var record : document.records()) {
            assertThat(record.path("prepared_prompt_text").asText()).doesNotContain("instruction", "Safe", "Unsafe");
            assertThat(record.has("human_label")).isFalse();
            assertThat(record.path("source_entry").path("source_query_en").asText()).isEqualTo("Supplied original");
            assertThat(record.path("source_task").path("gold").path("reference_source").asText()).contains("human annotation");
            assertThat(record.path("context_attributes").path("recipient.background").asText()).isEqualTo("Supplied context, unchanged");
        }
    }

    @Test void rejectsDuplicatesInvalidFactorsAndSourceInputConflicts() {
        var source = fixture(); source.add(source.get(0).deepCopy());
        assertThat(parse(source).errors().toString()).contains("duplicate instance_id", "duplicate base/context/task/language");
        source = fixture(); var entry = (ObjectNode) source.get(0);
        entry.remove("context_intended_to_be_safe"); entry.put("language", "xx");
        ((ObjectNode)entry.path("task_derivatives").path("nlu")).put("task_code", "BAD");
        assertThat(parse(source).errors().toString()).contains("context_intended_to_be_safe: required field is missing", "language: must be one of", "task_code: must be one of");
        source = fixture(); ((ObjectNode)source.get(0).path("task_derivatives").path("nlg").path("input")).put("base_id", "OTHER");
        assertThat(parse(source).errors().toString()).contains("input.base_id must match");
        source = fixture(); ((ObjectNode)source.get(0).path("task_derivatives")).remove("nlr");
        assertThat(parse(source).errors().toString()).contains("nlr: required field is missing");
    }

    @Test void acceptsOnlyTheObservedContextVariantsAndRejectsMissingContext() {
        var source = fixture();
        var entry = (ObjectNode)source.get(0); entry.putNull("context");
        entry.path("task_derivatives").forEach(task -> ((ObjectNode)task.path("input")).putNull("context"));
        assertThat(parse(source).errors().toString()).contains("$[0].context: expected");
        source = fixture(); entry = (ObjectNode)source.get(1);
        ((ObjectNode)entry.path("context").path("recipient")).putObject("background")
            .put("previous_experience", "Synthetic experience").put("purpose_of_query", "Synthetic purpose").put("demographics", "Synthetic demographics");
        ((ObjectNode)entry.path("context").path("transmission_principle")).put("urgency", "Synthetic urgency");
        JsonNode context = entry.path("context");
        entry.path("task_derivatives").forEach(task -> ((ObjectNode)task.path("input")).set("context", context.deepCopy()));
        assertThat(parse(source).valid()).isTrue();
        assertThat(parse(source).records().get(3).path("context_attributes").path("recipient.background.demographics").asText()).isEqualTo("Synthetic demographics");
    }

    @Test void rejectsExtraMissingRenamedAndWronglyTypedNestedFields() {
        var source = fixture(); ((ObjectNode)source.get(0)).put("unexpected", "value");
        assertThat(parse(source).errors().toString()).contains("$[0].unexpected: unexpected field");
        source = fixture(); ((ObjectNode)source.get(0).path("task_derivatives").path("nlr").path("output_schema").path("relationships").get(0)).put("extra", true);
        assertThat(parse(source).errors().toString()).contains("relationships[0].extra: unexpected field");
        source = fixture(); var task = (ObjectNode)source.get(0).path("task_derivatives").path("nlu");
        task.remove("research_role"); task.put("researchRole", "renamed");
        assertThat(parse(source).errors().toString()).contains("research_role: required field is missing", "researchRole: unexpected field");
        source = fixture(); ((ObjectNode)source.get(0).path("task_derivatives").path("nlg").path("gold")).put("binary_rating", "1");
        assertThat(parse(source).errors().toString()).contains("gold.binary_rating: expected");
        source = fixture(); ((ObjectNode)source.get(1).path("task_derivatives").path("nlg").path("input")).remove("source_query_en");
        assertThat(parse(source).valid()).isFalse();
    }

    @Test void structureDoesNotRequireTheSameContentsCountOrKeyOrder() throws Exception {
        var source = fixture(); var entry = (ObjectNode)source.get(0);
        var partial = new ObjectMapper().createArrayNode().add(entry);
        entry.put("query", "A different source query");
        assertThat(parse(partial).valid()).isTrue();
        assertThat(parse(partial).records()).hasSize(3);
        assertThat(importer.parse(partial.toPrettyString().getBytes(StandardCharsets.UTF_8)).valid()).isTrue();
        assertThat(importer.parse(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("samples/synthetic-provisional-v1.json"))).errors().toString()).contains("$: expected");
    }

    @Test void rejectsDuplicateJsonKeysTrailingTokensAndEmptyArrays() {
        assertThat(importer.parse("[{\"base_id\":\"A\",\"base_id\":\"B\"}]".getBytes()).valid()).isFalse();
        assertThat(importer.parse("[] []".getBytes()).valid()).isFalse();
        assertThat(importer.parse("[]".getBytes()).valid()).isFalse();
    }
    @Test void downloadableSyntheticSampleMatchesTheUploadContract() throws Exception {
        var bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/resources/static/samples/synthetic-tfg.json"));
        var document = importer.parse(bytes);
        assertThat(document.errors()).isEmpty();
        assertThat(document.records()).hasSize(12);
        assertThat(Coverage.of(document.records()).missingVariants()).isEmpty();
    }

}
