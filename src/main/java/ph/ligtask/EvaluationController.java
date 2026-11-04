
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
import ph.ligtask.llm.ModelConnectionStatus;
import ph.ligtask.llm.ModelConnectionStatusService;

@Controller
@RequestMapping("/evaluation")
public class EvaluationController {

    private final DatasetStore store;
    private final ObjectMapper mapper;
    private final ModelConnectionStatusService statusService;
    private final ph.ligtask.llm.LigtaskPromptBuilderService promptBuilder;
    private final ph.ligtask.runs.RunCoordinator runCoordinator;
    private final ph.ligtask.runs.RunStore runStore;
    private final StatisticalAnalysis statisticalAnalysis;
    private final MetricExport metricExport;

    public EvaluationController(
        DatasetStore store,
        ObjectMapper mapper,
        ModelConnectionStatusService statusService,
        ph.ligtask.llm.LigtaskPromptBuilderService promptBuilder,
        ph.ligtask.runs.RunCoordinator runCoordinator,
        ph.ligtask.runs.RunStore runStore,
        StatisticalAnalysis statisticalAnalysis,
        MetricExport metricExport
    ) {
        this.store = store;
        this.mapper = mapper;
        this.statusService = statusService;
        this.promptBuilder = promptBuilder;
        this.runCoordinator = runCoordinator;
        this.runStore = runStore;
        this.statisticalAnalysis = statisticalAnalysis;
        this.metricExport = metricExport;
    }

    @GetMapping
    String entry(Model model) {
        var datasets = store.all();

        if (!datasets.isEmpty()) {
            return "redirect:/evaluation/" + datasets.get(0).get("id");
        }

        return "evaluation-empty";
    }

    private void setup(
        UUID id,
        EvaluationView.Filters filters,
        Model model,
        String section,
        String modelKey
    ) {
        try {
            model.addAttribute("dataset", store.dataset(id));
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        model.addAttribute("datasetId", id);
        model.addAttribute("hasSavedRuns", !runStore.history(id).isEmpty());

        var datasets = new ArrayList<>(store.all());

        // Retained links can still open an archived dataset without making it active again.
        if (datasets.stream().noneMatch(dataset -> dataset.get("id").equals(id))) {
            datasets.add(0, store.dataset(id));
        }

        model.addAttribute("datasets", datasets);

        var selected = modelKey == null ? null : EvaluationView.model(modelKey);

        model.addAttribute("selectedModel", selected);

        model.addAttribute(
            "modelResultsUrl",
            selected == null
                ? null
                : url("/evaluation/" + id + "/models/" + selected.key(), filters, null)
        );

        var datasetLinks = new LinkedHashMap<UUID, String>();

        for (var dataset : datasets) {
            UUID datasetId = (UUID) dataset.get("id");

            String suffix = switch (section) {
                case "coverage" -> "/coverage";
                case "runs" -> "/runs";
                case "analysis" -> "/analysis";
                case "model", "prompt" ->
                    selected == null ? "/prompts" : "/models/" + selected.key();
                case "prompts" -> "/prompts";
                default -> "";
            };

            datasetLinks.put(
                datasetId,
                url(
                    "/evaluation/" + datasetId + suffix,
                    filters.atPage(0),
                    section.equals("coverage") ? modelKey : null
                )
            );
        }

        model.addAttribute("datasetLinks", datasetLinks);
        model.addAttribute("models", EvaluationView.MODELS);

        var modelLinks = new LinkedHashMap<String, String>();

        for (var choice : EvaluationView.MODELS) {
            modelLinks.put(
                choice.key(),
                url("/evaluation/" + id + "/models/" + choice.key(), filters, null)
            );
        }

        model.addAttribute("modelLinks", modelLinks);
        model.addAttribute("metricReport", EvaluationMetrics.compute(List.of()));
        model.addAttribute("metricRunId", null);
        model.addAttribute("selectedRunId", null);
        model.addAttribute("runChoices", List.of());
        model.addAttribute("savedModelResults", Map.of());
        model.addAttribute("metrics", EvaluationView.METRICS);
        model.addAttribute("filters", filters);
        model.addAttribute("overviewUrl", url("/evaluation/" + id, filters.atPage(0), null));
        model.addAttribute("promptsUrl", url("/evaluation/" + id + "/prompts", filters, null));
        model.addAttribute("coverageUrl", url("/evaluation/" + id + "/coverage", filters, modelKey));
        model.addAttribute("runsUrl", url("/evaluation/" + id + "/runs", filters, null));
        model.addAttribute("analysisUrl", "/evaluation/" + id + "/analysis");
    }

    @GetMapping("/{id}/analysis")
    String analysis(@PathVariable UUID id, @RequestParam(required = false) UUID run, Model model) {
        var filters = new EvaluationView.Filters("", "", "", "", 0);
        setup(id, filters, model, "analysis", null);
        var history = runStore.history(id);
        var finished = history.stream().filter(r -> !List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(r.get("status"))).toList();
        UUID selected = run != null ? run : finished.isEmpty() ? null : (UUID) finished.get(0).get("id");
        List<Map<String, Object>> responses = List.of();
        Map<String, Object> selectedRun = null;
        if (selected != null) {
            try { selectedRun = runStore.run(id, selected); }
            catch (EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
            if (List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(selectedRun.get("status")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for this run to finish before analyzing it.");
            responses = runStore.export(selected);
        }
        model.addAttribute("analysisRuns", finished);
        model.addAttribute("analysisRun", selectedRun);
        model.addAttribute("analysisRunId", selected);
        model.addAttribute("analysisEffects", StatisticalAnalysis.EFFECTS);
        var report = statisticalAnalysis.assess(id, selected, store.records(id), responses);
        if (selectedRun != null) {
            report.put("run_status", selectedRun.get("status"));
            report.put("run_settings", selectedRun.get("settings"));
            report.put("run_plan_hash", selectedRun.get("plan_hash"));
        }
        var providerModels = new TreeMap<String, Set<String>>();
        for (var response : responses) {
            if (response.get("model_id") != null) providerModels.computeIfAbsent(response.get("model_key").toString(), ignored -> new TreeSet<>()).add(response.get("model_id").toString());
        }
        report.put("provider_model_ids", providerModels);
        model.addAttribute("analysis", report);
        return "evaluation-analysis";
    }

    @GetMapping("/{id}/analysis.json")
    @ResponseBody
    ResponseEntity<Object> analysisJson(@PathVariable UUID id, @RequestParam(required = false) UUID run) {
        var model = new org.springframework.ui.ExtendedModelMap();
        analysis(id, run, model);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ligtask-statistical-analysis.json\"")
            .body(model.get("analysis"));
    }

    @GetMapping("/{id}")
    String overview(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "") String task,
        @RequestParam(defaultValue = "") String language,
        @RequestParam(defaultValue = "") String context,
        @RequestParam(defaultValue = "") String q,
        @RequestParam(required = false) UUID run,
        Model model
    ) {
        var filters = new EvaluationView.Filters(task, language, context, q, 0);

        setup(id, filters, model, "overview", null);
        chooseRun(id,null,run,model);
        model.addAttribute("formPath","/evaluation/"+id);

        var page = EvaluationView.filter(store.records(id),filters);
        model.addAttribute("page",page);
        var overviewMetrics = new LinkedHashMap<String,EvaluationMetrics.Report>();
        for(var choice:EvaluationView.MODELS) overviewMetrics.put(choice.key(),modelEvaluation(id,choice.key(),page.matching(),run).metrics());
        model.addAttribute("overviewMetrics",overviewMetrics);

        return "evaluation-overview";
    }

    @GetMapping("/{id}/runs")
    String runs(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "") String task,
        @RequestParam(defaultValue = "") String language,
        @RequestParam(defaultValue = "") String context,
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "0") int page,
        Model model
    ) throws Exception {

        var filters = new EvaluationView.Filters(task, language, context, q, page);

        setup(id, filters, model, "runs", null);

        var records = store.records(id);

        model.addAttribute("promptCount", records.size());
        model.addAttribute("modelAvailability", runCoordinator.availability());
        model.addAttribute("runApi", "/evaluation/" + id + "/runs/api");

        // Retrieve the latest API connection status for each configured model.
        var connectionStatuses = new LinkedHashMap<String, ModelConnectionStatus>();

        for (var choice : EvaluationView.MODELS) {
            connectionStatuses.put(
                choice.key(),
                statusService.getStatus(choice.key())
            );
        }

        model.addAttribute("connectionStatuses", connectionStatuses);

        var previews = new ArrayList<Map<String, String>>();

        // Explicit display whitelist: source gold, annotations and raw metadata never enter this preview.
        for (var row : records.stream().limit(6).toList()) {

            JsonNode raw = mapper.readTree(row.get("raw_record").toString());

            var input = mapper.createObjectNode();

            if (raw.path("task_instruction").isTextual()) {
                input.set("instruction", raw.get("task_instruction"));
            }

            input.put("query", row.get("prepared_prompt_text").toString());
            input.put("language", row.get("language_variant").toString());

            if (raw.path("source_entry").path("context").isObject()) {
                input.set("context", raw.path("source_entry").get("context"));
            } else if (row.get("context_text") != null) {
                input.put("context", row.get("context_text").toString());
            } else {
                input.putNull("context");
            }

            if (row.get("policy_text") != null) {
                input.put("policy", row.get("policy_text").toString());
            }

            try {
                var prepared = promptBuilder.prepareRecord(id, row);
                input.set("expected_output_format", ph.ligtask.llm.LigtaskOutputContract.schema(prepared.taskType()));
                input.put("evaluation_prompt", prepared.prompt());
                input.put("prompt_sha256", prepared.promptSha256());
                input.put("contract_version", prepared.contractVersion());
            } catch (IllegalArgumentException e) {
                input.put("validation_error", e.getMessage());
            }

            previews.add(
                Map.of(
                    "identifier", row.get("instance_id").toString(),
                    "content", input.toPrettyString()
                )
            );
        }

        model.addAttribute("inputPreviews", previews);

        return "evaluation-runs";
    }

    @GetMapping("/{id}/coverage")
    String coverage(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "") String task,
        @RequestParam(defaultValue = "") String language,
        @RequestParam(defaultValue = "") String context,
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false, name = "model") String modelKey,
        Model model
    ) throws Exception {

        var filters = new EvaluationView.Filters(task, language, context, q, page);

        setup(id, filters, model, "coverage", modelKey);

        var rows = store.records(id);
        var raw = new ArrayList<JsonNode>();

        for (var row : rows) {
            raw.add(mapper.readTree(row.get("raw_record").toString()));
        }

        model.addAttribute("coverage", Coverage.of(raw));

        return "evaluation-coverage";
    }

    @GetMapping({"/{id}/models/{modelKey}", "/{id}/prompts"})
    String records(
        @PathVariable UUID id,
        @PathVariable(required = false) String modelKey,
        @RequestParam(defaultValue = "") String task,
        @RequestParam(defaultValue = "") String language,
        @RequestParam(defaultValue = "") String context,
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) UUID run,
        Model model
    ) {

        var filters = new EvaluationView.Filters(task, language, context, q, page);

        setup(id, filters, model, modelKey == null ? "prompts" : "model", modelKey);

        var selected = modelKey == null ? null : EvaluationView.model(modelKey);
        UUID chosenRun=chooseRun(id,modelKey,run,model);

        model.addAttribute("selectedModel", selected);

        String path = selected == null
            ? "/evaluation/" + id + "/prompts"
            : "/evaluation/" + id + "/models/" + selected.key();

        var result = EvaluationView.filter(store.records(id), filters);

        model.addAttribute("page", result);
        var humanReferences = new HashMap<Object,HumanReference>();
        for(var prompt:result.rows()) humanReferences.put(prompt.get("id"),HumanReference.from(prompt));
        model.addAttribute("humanReferences",humanReferences);
        if (selected != null) {
            var evaluation = modelEvaluation(id,selected.key(),result.matching(),chosenRun);
            model.addAttribute("metricRunId",evaluation.runId());
            model.addAttribute("metricReport",evaluation.metrics());
            model.addAttribute("savedModelResults",evaluation.responses());
        }
        model.addAttribute("formPath", path);
        model.addAttribute("resetUrl", withRun(path,chosenRun));
        model.addAttribute("exportUrl", url("/evaluation/" + id + "/export.csv", filters, modelKey));
        model.addAttribute("metricCsvUrl", "/evaluation/" + id + "/metrics.csv");
        model.addAttribute("metricZipUrl", withRun(url("/evaluation/" + id + "/metrics.zip", filters.atPage(0), null),chosenRun));
        model.addAttribute("metricManifestUrl", "/evaluation/" + id + "/metrics-manifest.json");
        model.addAttribute("exportRuns", runStore.finished(id,null));
        model.addAttribute("previousUrl", withRun(url(path, filters.atPage(Math.max(0, result.number() - 1)), null),chosenRun));
        model.addAttribute("nextUrl", withRun(url(path, filters.atPage(result.number() + 1), null),chosenRun));

        var links = new HashMap<UUID, String>();

        for (var row : result.rows()) {
            links.put(
                (UUID) row.get("id"),
                withRun(url(
                    "/evaluation/" + id + "/prompts/" + row.get("id"),
                    filters.atPage(result.number()),
                    modelKey
                ),chosenRun)
            );
        }

        model.addAttribute("promptLinks", links);

        return "evaluation-results";
    }

    private record ModelEvaluation(UUID runId,Map<Object,Map<String,Object>> responses,EvaluationMetrics.Report metrics) {}

    private ModelEvaluation modelEvaluation(UUID dataset,String modelKey,List<Map<String,Object>> prompts) {
        return modelEvaluation(dataset,modelKey,prompts,null);
    }
    private ModelEvaluation modelEvaluation(UUID dataset,String modelKey,List<Map<String,Object>> prompts,UUID requested) {
        var saved = new HashMap<Object,Map<String,Object>>();
        UUID runId = requested!=null?requested:runStore.latestFinishedForModel(dataset,modelKey).map(r->(UUID)r.get("id")).orElse(null);
        if(runId != null) for(var response:runStore.metricRows(runId,modelKey)) saved.put(response.get("prompt_id"),response);
        var samples = new ArrayList<EvaluationMetrics.Sample>();
        for(var prompt:prompts) {
            var reference = HumanReference.from(prompt);
            var response = saved.get(prompt.get("id"));
            boolean valid = response != null && "VALID".equals(response.get("status"));
            String label = valid ? Objects.toString(response.get("classification"),null) : null;
            if(label != null) try { EvaluationMetrics.binary(prompt.get("task_type").toString(),label); }
                catch(IllegalArgumentException e) { label = null; }
            Double rating = valid && response.get("safety_rating") instanceof Number n ? n.doubleValue() : null;
            samples.add(new EvaluationMetrics.Sample(prompt.get("task_type").toString(),valid,
                reference.canonicalLabel(),label,reference.safetyRating(),rating,
                valid && response.get("positive_probability") instanceof Number n ? n.doubleValue() : null));
        }
        return new ModelEvaluation(runId,saved,EvaluationMetrics.compute(samples));
    }
    private UUID chooseRun(UUID dataset,String modelKey,UUID requested,Model model){
        if(requested!=null){
            try{
                var saved=runStore.run(dataset,requested);
                if(ph.ligtask.runs.RunStore.isActive(saved.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose a finished saved run to view results.");
            }catch(EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        }
        var latest=modelKey==null?Optional.<Map<String,Object>>empty():runStore.latestFinishedForModel(dataset,modelKey);
        UUID chosen=requested!=null?requested:latest.map(r->(UUID)r.get("id")).orElse(null);
        var choices=new ArrayList<>(runStore.finished(dataset,modelKey));
        if(chosen!=null&&choices.stream().noneMatch(r->chosen.equals(r.get("id"))))choices.add(requested==null?latest.orElseThrow():runStore.run(dataset,chosen));
        model.addAttribute("selectedRunId",chosen);model.addAttribute("runChoices",choices);
        var links=new LinkedHashMap<String,String>();
        var filters=(EvaluationView.Filters)model.getAttribute("filters");
        for(var choice:EvaluationView.MODELS)links.put(choice.key(),withRun(url("/evaluation/"+dataset+"/models/"+choice.key(),filters,null),chosen));
        model.addAttribute("modelLinks",links);
        model.addAttribute("overviewUrl",withRun(url("/evaluation/"+dataset,filters.atPage(0),null),chosen));
        return chosen;
    }
    private static String withRun(String url,UUID run){return run==null?url:UriComponentsBuilder.fromUriString(url).queryParam("run",run).build().toUriString();}

    @GetMapping("/{id}/prompts/{promptId}")
    String prompt(
        @PathVariable UUID id,
        @PathVariable UUID promptId,
        @RequestParam(defaultValue = "") String task,
        @RequestParam(defaultValue = "") String language,
        @RequestParam(defaultValue = "") String context,
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false, name = "model") String modelKey,
        @RequestParam(required = false) UUID run,
        Model model
    ) throws Exception {

        var filters = new EvaluationView.Filters(task, language, context, q, page);

        setup(id, filters, model, "prompt", modelKey);

        var selected = modelKey == null ? null : EvaluationView.model(modelKey);
        UUID chosenRun=chooseRun(id,modelKey,run,model);
        var all = store.records(id);

        var row = all.stream()
            .filter(r -> r.get("id").equals(promptId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        var result = EvaluationView.filter(all, filters);

        // A linked prompt must belong to this dataset and its selected filter set.
        if (!result.matching().contains(row)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        int index = result.matching().indexOf(row);

        model.addAttribute("prompt", row);
        model.addAttribute("humanReference", HumanReference.from(row));
        model.addAttribute("selectedModel", selected);
        model.addAttribute("formPath","/evaluation/"+id+"/prompts/"+promptId);
        var response=chosenRun!=null&&selected!=null?runStore.promptResult(chosenRun,promptId,modelKey).orElse(null):null;
        model.addAttribute("promptResponse",response);
        model.addAttribute("responseJson",response==null||response.get("response")==null?null:mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response.get("response")));
        model.addAttribute("position", index + 1);
        model.addAttribute("matchingCount", result.count());

        String path = selected == null
            ? "/evaluation/" + id + "/prompts"
            : "/evaluation/" + id + "/models/" + selected.key();

        model.addAttribute("backUrl", withRun(url(path, filters, null),chosenRun));

        var nav = new ArrayList<Map<String, Object>>();

        int first = (index / EvaluationView.PAGE_SIZE) * EvaluationView.PAGE_SIZE;

        for (var item : result.matching().subList(
            first,
            Math.min(first + EvaluationView.PAGE_SIZE, result.count())
        )) {
            nav.add(
                Map.of(
                    "id", item.get("id"),
                    "name", item.get("instance_id"),
                    "url", withRun(url(
                        "/evaluation/" + id + "/prompts/" + item.get("id"),
                        filters,
                        modelKey
                    ),chosenRun)
                )
            );
        }

        model.addAttribute("navigation", nav);

        model.addAttribute(
            "previousPrompt",
            index == 0
                ? null
                : withRun(url(
                    "/evaluation/" + id + "/prompts/" + result.matching().get(index - 1).get("id"),
                    filters,
                    modelKey
                ),chosenRun)
        );

        model.addAttribute(
            "nextPrompt",
            index + 1 == result.count()
                ? null
                : withRun(url(
                    "/evaluation/" + id + "/prompts/" + result.matching().get(index + 1).get("id"),
                    filters,
                    modelKey
                ),chosenRun)
        );

        Object storedRaw=row.get("raw_record");
        JsonNode raw = storedRaw instanceof Map<?,?>?mapper.valueToTree(storedRaw):mapper.readTree(storedRaw.toString());

        model.addAttribute(
            "category",
            raw.path("category").isTextual()
                ? raw.path("category").asText()
                : "Not supplied"
        );

        var attributes = new LinkedHashMap<String, String>();

        if (raw.path("context_attributes").isObject()) {
            raw.path("context_attributes").fields().forEachRemaining(e ->
                attributes.put(
                    e.getKey(),
                    e.getValue().isTextual()
                        ? e.getValue().asText()
                        : e.getValue().toString()
                )
            );
        }

        model.addAttribute("contextAttributes", attributes);
        model.addAttribute("taskInstruction", raw.path("task_instruction").asText(""));
        model.addAttribute(
            "outputSchema",
            raw.path("output_schema").isObject()
                ? raw.path("output_schema").toPrettyString()
                : ""
        );

        model.addAttribute(
            "entryLabel",
            raw.path("import_metadata").has("identifier_origin")
                ? "Derived task entry ID"
                : "Upstream entry"
        );

        model.addAttribute("rawJson", raw.toPrettyString());

        return "evaluation-prompt";
    }

    @GetMapping(value = "/{id}/export.csv", produces = "text/csv;charset=UTF-8")
    @ResponseBody
    ResponseEntity<byte[]> export(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "") String task,
        @RequestParam(defaultValue = "") String language,
        @RequestParam(defaultValue = "") String context,
        @RequestParam(defaultValue = "") String q,
        @RequestParam(required = false, name = "model") String modelKey
    ) {

        Map<String, Object> dataset;

        try {
            dataset = store.dataset(id);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        var selected = modelKey == null ? null : EvaluationView.model(modelKey);
        var filters = new EvaluationView.Filters(task, language, context, q, 0);
        var rows = EvaluationView.filter(store.records(id), filters).matching();

        StringBuilder csv = new StringBuilder(
            "\uFEFFdataset_id,dataset_name,dataset_version,synthetic,model_view,instance_id,upstream_entry_id,base_query_id,context_id,context_condition,task_type,language_variant,prepared_prompt_text,context_text,policy_text,human_label,model_label,comparison_status\r\n"
        );

        for (var row : rows) {

            List<Object> values = new ArrayList<>(
                List.of(
                    id,
                    dataset.get("name"),
                    dataset.get("version"),
                    dataset.get("synthetic"),
                    selected == null ? "" : selected.name()
                )
            );

            for (String field : List.of(
                "instance_id",
                "upstream_entry_id",
                "base_query_id",
                "context_id",
                "context_condition",
                "task_type",
                "language_variant",
                "prepared_prompt_text",
                "context_text",
                "policy_text"
            )) {
                values.add(row.get(field));
            }

            values.add(HumanReference.from(row).label());
            values.add("");
            values.add("NOT_EVALUATED");

            csv.append(
                values.stream()
                    .map(EvaluationController::csvCell)
                    .collect(java.util.stream.Collectors.joining(","))
            ).append("\r\n");
        }

        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"ligtask-prepared-prompts.csv\""
            )
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/{id}/metrics.{format}")
    @ResponseBody
    ResponseEntity<byte[]> exportMetrics(@PathVariable UUID id,@PathVariable String format,
        @RequestParam(defaultValue="") String task,@RequestParam(defaultValue="") String language,
        @RequestParam(defaultValue="") String context,@RequestParam(defaultValue="") String q,
        @RequestParam(required=false) UUID run) throws java.io.IOException {
        if(!Set.of("csv","zip").contains(format))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        MetricExport.Export data;
        try{data=metricExport.prepare(id,new EvaluationView.Filters(task,language,context,q,0),run);}
        catch(EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        boolean bundle=format.equals("zip");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"ligtask-all-models-metrics."+format+"\"")
            .contentType(bundle?MediaType.parseMediaType("application/zip"):new MediaType("text","csv",StandardCharsets.UTF_8))
            .body(bundle?metricExport.zip(data):data.csv());
    }

    @GetMapping("/{id}/metrics-manifest.json")
    @ResponseBody
    ResponseEntity<Object> exportMetricManifest(@PathVariable UUID id,
        @RequestParam(defaultValue="") String task,@RequestParam(defaultValue="") String language,
        @RequestParam(defaultValue="") String context,@RequestParam(defaultValue="") String q,
        @RequestParam(required=false) UUID run) {
        try{
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"ligtask-metrics-codebook.json\"")
                .body(metricExport.prepare(id,new EvaluationView.Filters(task,language,context,q,0),run).manifest());
        }catch(EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
    }

    static String csvCell(Object value) {
        String text = Objects.toString(value, "");
        String stripped = text.stripLeading();

        if ((!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0)
            || text.startsWith("\t")
            || text.startsWith("\r")
            || text.startsWith("\n")) {
            text = "'" + text;
        }

        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    static String url(
        String path,
        EvaluationView.Filters filters,
        String modelKey
    ) {
        var builder = UriComponentsBuilder.fromPath(path)
            .queryParam("task", filters.task())
            .queryParam("language", filters.language())
            .queryParam("context", filters.context())
            .queryParam("q", filters.q())
            .queryParam("page", filters.page());

        if (modelKey != null) {
            builder.queryParam("model", modelKey);
        }

        return builder.build().encode().toUriString();
    }
}
