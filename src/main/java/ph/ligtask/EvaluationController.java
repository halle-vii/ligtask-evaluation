package ph.ligtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
@RequestMapping("/evaluation")
public class EvaluationController {
    private final DatasetStore store;
    private final ObjectMapper mapper;

    public EvaluationController(DatasetStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @GetMapping
    String entry(Model model) {
        var datasets = store.all();
        if (!datasets.isEmpty()) return "redirect:/evaluation/" + datasets.get(0).get("id");
        return "evaluation-empty";
    }

    private void setup(UUID id, EvaluationView.Filters filters, Model model, String section, String modelKey) {
        try { model.addAttribute("dataset", store.dataset(id)); }
        catch (EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        model.addAttribute("datasetId", id);
        var datasets = new ArrayList<>(store.all());
        // Retained links can still open an archived dataset without making it active again.
        if (datasets.stream().noneMatch(dataset -> dataset.get("id").equals(id))) datasets.add(0, store.dataset(id));
        model.addAttribute("datasets", datasets);
        var selected = modelKey == null ? null : EvaluationView.model(modelKey);
        model.addAttribute("selectedModel", selected);
        model.addAttribute("modelResultsUrl", selected == null ? null : url("/evaluation/" + id + "/models/" + selected.key(), filters, null));
        var datasetLinks = new LinkedHashMap<UUID,String>();
        for (var dataset : datasets) {
            UUID datasetId = (UUID) dataset.get("id");
            String suffix = switch (section) {
                case "coverage" -> "/coverage";
                case "runs" -> "/runs";
                case "model", "prompt" -> selected == null ? "/prompts" : "/models/" + selected.key();
                case "prompts" -> "/prompts";
                default -> "";
            };
            datasetLinks.put(datasetId, url("/evaluation/" + datasetId + suffix, filters.atPage(0), section.equals("coverage") ? modelKey : null));
        }
        model.addAttribute("datasetLinks", datasetLinks);
        model.addAttribute("models", EvaluationView.MODELS);
        var modelLinks = new LinkedHashMap<String,String>();
        for (var choice : EvaluationView.MODELS)
            modelLinks.put(choice.key(), url("/evaluation/" + id + "/models/" + choice.key(), filters, null));
        model.addAttribute("modelLinks", modelLinks);
        model.addAttribute("metrics", EvaluationView.METRICS);
        model.addAttribute("filters", filters);
        model.addAttribute("overviewUrl", url("/evaluation/" + id, filters.atPage(0), null));
        model.addAttribute("promptsUrl", url("/evaluation/" + id + "/prompts", filters, null));
        model.addAttribute("coverageUrl", url("/evaluation/" + id + "/coverage", filters, modelKey));
        model.addAttribute("runsUrl", url("/evaluation/" + id + "/runs", filters, null));
    }

    @GetMapping("/{id}")
    String overview(@PathVariable UUID id,
                    @RequestParam(defaultValue="") String task,
                    @RequestParam(defaultValue="") String language,
                    @RequestParam(defaultValue="") String context,
                    @RequestParam(defaultValue="") String q, Model model) {
        var filters = new EvaluationView.Filters(task, language, context, q, 0);
        setup(id, filters, model, "overview", null);
        model.addAttribute("page", EvaluationView.filter(store.records(id), filters));
        return "evaluation-overview";
    }

    @GetMapping("/{id}/runs")
    String runs(@PathVariable UUID id,
                @RequestParam(defaultValue="") String task,
                @RequestParam(defaultValue="") String language,
                @RequestParam(defaultValue="") String context,
                @RequestParam(defaultValue="") String q,
                @RequestParam(defaultValue="0") int page, Model model) throws Exception {
        var filters = new EvaluationView.Filters(task, language, context, q, page);
        setup(id, filters, model, "runs", null);
        var records = store.records(id);
        model.addAttribute("promptCount", records.size());
        var previews = new ArrayList<Map<String,String>>();
        // Explicit display whitelist: source gold, annotations and raw metadata never enter this preview.
        for (var row : records.stream().limit(6).toList()) {
            JsonNode raw = mapper.readTree(row.get("raw_record").toString());
            var input = mapper.createObjectNode();
            if (raw.path("task_instruction").isTextual()) input.set("instruction", raw.get("task_instruction"));
            input.put("query", row.get("prepared_prompt_text").toString());
            input.put("language", row.get("language_variant").toString());
            if (raw.path("source_entry").path("context").isObject()) input.set("context", raw.path("source_entry").get("context"));
            else if (row.get("context_text") != null) input.put("context", row.get("context_text").toString());
            else input.putNull("context");
            if (row.get("policy_text") != null) input.put("policy", row.get("policy_text").toString());
            if (raw.path("output_schema").isObject()) input.set("expected_output_format", raw.get("output_schema"));
            previews.add(Map.of("identifier", row.get("instance_id").toString(), "content", input.toPrettyString()));
        }
        model.addAttribute("inputPreviews", previews);
        return "evaluation-runs";
    }

    @GetMapping("/{id}/coverage")
    String coverage(@PathVariable UUID id,
                    @RequestParam(defaultValue="") String task,
                    @RequestParam(defaultValue="") String language,
                    @RequestParam(defaultValue="") String context,
                    @RequestParam(defaultValue="") String q,
                    @RequestParam(defaultValue="0") int page,
                    @RequestParam(required=false, name="model") String modelKey, Model model) throws Exception {
        var filters = new EvaluationView.Filters(task, language, context, q, page);
        setup(id, filters, model, "coverage", modelKey);
        var rows = store.records(id);
        var raw = new ArrayList<JsonNode>();
        for (var row : rows) raw.add(mapper.readTree(row.get("raw_record").toString()));
        model.addAttribute("coverage", Coverage.of(raw));
        return "evaluation-coverage";
    }

    @GetMapping({"/{id}/models/{modelKey}", "/{id}/prompts"})
    String records(@PathVariable UUID id, @PathVariable(required=false) String modelKey,
                   @RequestParam(defaultValue="") String task,
                   @RequestParam(defaultValue="") String language,
                   @RequestParam(defaultValue="") String context,
                   @RequestParam(defaultValue="") String q,
                   @RequestParam(defaultValue="0") int page, Model model) {
        var filters = new EvaluationView.Filters(task, language, context, q, page);
        setup(id, filters, model, modelKey == null ? "prompts" : "model", modelKey);
        var selected = modelKey == null ? null : EvaluationView.model(modelKey);
        model.addAttribute("selectedModel", selected);
        String path = selected == null ? "/evaluation/" + id + "/prompts" : "/evaluation/" + id + "/models/" + selected.key();
        var result = EvaluationView.filter(store.records(id), filters);
        model.addAttribute("page", result);
        model.addAttribute("formPath", path);
        model.addAttribute("resetUrl", path);
        model.addAttribute("exportUrl", url("/evaluation/" + id + "/export.csv", filters, modelKey));
        model.addAttribute("previousUrl", url(path, filters.atPage(Math.max(0, result.number()-1)), null));
        model.addAttribute("nextUrl", url(path, filters.atPage(result.number()+1), null));
        var links = new HashMap<UUID, String>();
        for (var row : result.rows()) links.put((UUID) row.get("id"), url("/evaluation/" + id + "/prompts/" + row.get("id"), filters.atPage(result.number()), modelKey));
        model.addAttribute("promptLinks", links);
        return "evaluation-results";
    }

    @GetMapping("/{id}/prompts/{promptId}")
    String prompt(@PathVariable UUID id, @PathVariable UUID promptId,
                  @RequestParam(defaultValue="") String task,
                  @RequestParam(defaultValue="") String language,
                  @RequestParam(defaultValue="") String context,
                  @RequestParam(defaultValue="") String q,
                  @RequestParam(defaultValue="0") int page,
                  @RequestParam(required=false, name="model") String modelKey, Model model) throws Exception {
        var filters = new EvaluationView.Filters(task, language, context, q, page);
        setup(id, filters, model, "prompt", modelKey);
        var selected = modelKey == null ? null : EvaluationView.model(modelKey);
        var all = store.records(id);
        var row = all.stream().filter(r -> r.get("id").equals(promptId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var result = EvaluationView.filter(all, filters);
        // A linked prompt must belong to this dataset and its selected filter set.
        if (!result.matching().contains(row)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        int index = result.matching().indexOf(row);
        model.addAttribute("prompt", row);
        model.addAttribute("selectedModel", selected);
        model.addAttribute("position", index + 1);
        model.addAttribute("matchingCount", result.count());
        String path = selected == null ? "/evaluation/" + id + "/prompts" : "/evaluation/" + id + "/models/" + selected.key();
        model.addAttribute("backUrl", url(path, filters, null));
        var nav = new ArrayList<Map<String,Object>>();
        int first = (index / EvaluationView.PAGE_SIZE) * EvaluationView.PAGE_SIZE;
        for (var item : result.matching().subList(first, Math.min(first + EvaluationView.PAGE_SIZE, result.count()))) {
            nav.add(Map.of("id", item.get("id"), "name", item.get("instance_id"), "url",
                url("/evaluation/" + id + "/prompts/" + item.get("id"), filters, modelKey)));
        }
        model.addAttribute("navigation", nav);
        model.addAttribute("previousPrompt", index == 0 ? null : url("/evaluation/" + id + "/prompts/" + result.matching().get(index-1).get("id"), filters, modelKey));
        model.addAttribute("nextPrompt", index+1 == result.count() ? null : url("/evaluation/" + id + "/prompts/" + result.matching().get(index+1).get("id"), filters, modelKey));
        JsonNode raw = mapper.readTree(row.get("raw_record").toString());
        model.addAttribute("category", raw.path("category").isTextual() ? raw.path("category").asText() : "Not supplied");
        var attributes = new LinkedHashMap<String,String>();
        if (raw.path("context_attributes").isObject()) raw.path("context_attributes").fields().forEachRemaining(e -> attributes.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString()));
        model.addAttribute("contextAttributes", attributes);
        model.addAttribute("taskInstruction", raw.path("task_instruction").asText(""));
        model.addAttribute("outputSchema", raw.path("output_schema").isObject() ? raw.path("output_schema").toPrettyString() : "");
        model.addAttribute("entryLabel", raw.path("import_metadata").has("identifier_origin") ? "Derived task entry ID" : "Upstream entry");
        model.addAttribute("rawJson", raw.toPrettyString());
        return "evaluation-prompt";
    }

    @GetMapping(value="/{id}/export.csv", produces="text/csv;charset=UTF-8")
    @ResponseBody
    ResponseEntity<byte[]> export(@PathVariable UUID id,
                                 @RequestParam(defaultValue="") String task,
                                 @RequestParam(defaultValue="") String language,
                                 @RequestParam(defaultValue="") String context,
                                 @RequestParam(defaultValue="") String q,
                                 @RequestParam(required=false, name="model") String modelKey) {
        Map<String,Object> dataset;
        try { dataset = store.dataset(id); }
        catch (EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        var selected = modelKey == null ? null : EvaluationView.model(modelKey);
        var filters = new EvaluationView.Filters(task, language, context, q, 0);
        var rows = EvaluationView.filter(store.records(id), filters).matching();
        StringBuilder csv = new StringBuilder("\uFEFFdataset_id,dataset_name,dataset_version,synthetic,model_view,instance_id,upstream_entry_id,base_query_id,context_id,context_condition,task_type,language_variant,prepared_prompt_text,context_text,policy_text,human_label,model_label,comparison_status\r\n");
        for (var row : rows) {
            List<Object> values = new ArrayList<>(List.of(id, dataset.get("name"), dataset.get("version"), dataset.get("synthetic"), selected == null ? "" : selected.name()));
            for (String field : List.of("instance_id","upstream_entry_id","base_query_id","context_id","context_condition","task_type","language_variant","prepared_prompt_text","context_text","policy_text")) values.add(row.get(field));
            values.add(""); values.add(""); values.add("NOT_EVALUATED");
            csv.append(values.stream().map(EvaluationController::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ligtask-prepared-prompts.csv\"")
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String csvCell(Object value) {
        String text = Objects.toString(value, "");
        String stripped = text.stripLeading();
        if ((!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    static String url(String path, EvaluationView.Filters filters, String modelKey) {
        var builder = UriComponentsBuilder.fromPath(path).queryParam("task", filters.task())
            .queryParam("language", filters.language()).queryParam("context", filters.context())
            .queryParam("q", filters.q()).queryParam("page", filters.page());
        if (modelKey != null) builder.queryParam("model", modelKey);
        return builder.build().encode().toUriString();
    }
}
