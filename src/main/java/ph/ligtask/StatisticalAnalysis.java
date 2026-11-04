package ph.ligtask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Reads exactly one saved run. No provider access and no inferred human labels. */
@Service
public class StatisticalAnalysis {
    public static final List<String> EFFECTS = List.of("Context", "Language", "Task", "C × L", "C × T", "L × T", "C × L × T");
    private final ObjectMapper json;
    private final String python;

    public StatisticalAnalysis(ObjectMapper json,
        @Value("${ligtask.analysis.python:${LIGTASK_ANALYSIS_PYTHON:.local/analysis-venv/bin/python}}") String python) {
        this.json = json; this.python = python;
    }

    public Map<String, Object> assess(UUID dataset, UUID runId, List<Map<String, Object>> prompts,
                                       List<Map<String, Object>> saved) {
        var byId = new HashMap<Object, Map<String, Object>>();
        prompts.forEach(p -> byId.put(p.get("id"), p));
        var rows = new ArrayList<Map<String, Object>>();
        for (var item : saved) {
            var prompt = byId.get(item.get("prompt_id"));
            if (prompt == null) throw new IllegalArgumentException("Run contains a prompt outside the dataset.");
            var row = new LinkedHashMap<String, Object>();
            row.put("instance_id", prompt.get("instance_id")); row.put("base", prompt.get("base_query_id"));
            row.put("context", prompt.get("context_condition")); row.put("language", prompt.get("language_variant"));
            row.put("task", prompt.get("task_type")); row.put("model", item.get("model_key"));
            row.put("status", item.get("status")); row.put("rating", item.get("safety_rating"));
            row.put("model_label", item.get("classification"));
            row.put("human_label", HumanReference.from(prompt).canonicalLabel());
            rows.add(row);
        }
        Map<String, Object> report;
        boolean enough = EvaluationView.MODELS.stream().anyMatch(m -> completeBases(rows, m.key(),false) >= 30 || completeBases(rows,m.key(),true) >= 30);
        if (enough) {
            try { report = calculate(rows); }
            catch (IOException | IllegalStateException e) {
                report = pending(rows, "Local statistical computation is unavailable. Check the analysis runtime setup.");
            }
        } else report = pending(rows, "");
        report.put("dataset_id", dataset.toString()); report.put("run_id", runId == null ? null : runId.toString());
        report.put("generated_at", Instant.now().toString()); report.put("human_reference_version", "dataset:"+dataset+":gold.label");
        report.put("generated_label", java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Manila"))
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, uuuu, h:mm a", Locale.ENGLISH)) + " (Asia/Manila)");
        report.put("reference_status", "Human classifications use the confirmed task gold.label mapping. Human means from gold.rating_mean supply PCC separately; these tables analyze correctness and model ratings.");
        decorate(report);
        return report;
    }

    private int completeBases(List<Map<String, Object>> rows, String model, boolean correctness) {
        var bases = new HashMap<Object, Set<String>>();
        for (var r : rows) {
            boolean usable = correctness ? r.get("human_label") != null && validLabel(r.get("task"),r.get("model_label"))
                : r.get("rating") instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 1 && n.doubleValue() <= 10;
            if (!model.equals(r.get("model")) || !"VALID".equals(r.get("status")) || !usable ||
                !Set.of("SAFE", "UNSAFE").contains(Objects.toString(r.get("context"), "")) ||
                !Set.of("EN", "FIL").contains(Objects.toString(r.get("language"), "")) ||
                !Set.of("NLU", "NLR", "NLG").contains(Objects.toString(r.get("task"), ""))) continue;
            bases.computeIfAbsent(r.get("base"), ignored -> new HashSet<>())
                .add(r.get("context") + "/" + r.get("language") + "/" + r.get("task"));
        }
        return (int) bases.values().stream().filter(c -> c.size() == 12).count();
    }

    private boolean validLabel(Object task,Object label) {
        if(task==null||label==null)return false;
        try{EvaluationMetrics.binary(task.toString(),label.toString());return true;}
        catch(IllegalArgumentException e){return false;}
    }

    private Map<String, Object> calculate(List<Map<String, Object>> rows) throws IOException {
        Path directory = Files.createTempDirectory("ligtask-analysis-");
        Process process = null;
        try {
            Path script = directory.resolve("three_factor.py"), output = directory.resolve("report.json"), errors = directory.resolve("error.log");
            try (var resource = getClass().getResourceAsStream("/analysis/three_factor.py")) {
                if (resource == null) throw new IOException("Missing analysis worker");
                Files.copy(resource, script);
            }
            process = new ProcessBuilder(python, script.toString()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
            var models = EvaluationView.MODELS.stream().map(m -> Map.of("key", m.key(), "name", m.name())).toList();
            try (var stdin = process.getOutputStream()) { json.writeValue(stdin, Map.of("models", models, "rows", rows)); }
            if (!process.waitFor(45, TimeUnit.SECONDS)) throw new IOException("Analysis timed out");
            if (process.exitValue() != 0 || Files.size(output) > 2_000_000) throw new IOException("Analysis failed");
            Map<String, Object> report = json.readValue(output.toFile(), new TypeReference<>() {});
            if (report.containsKey("error") || !(report.get("tables") instanceof List<?>)) throw new IOException("Invalid statistical result");
            return report;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Analysis interrupted", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            try (var files = Files.list(directory)) {
                for (var path : files.toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(directory);
        }
    }

    private Map<String, Object> pending(List<Map<String, Object>> rows, String runtimeError) {
        var report = new LinkedHashMap<String, Object>();
        report.put("version", "three-factor-v1"); report.put("minimum_bases", 30); report.put("alpha", .05);
        report.put("correction", "Holm across 98 planned effect tests; unavailable tests retained with p=1");
        var tables = new ArrayList<Map<String, Object>>();
        for (String outcome : List.of("correctness", "ratings")) {
            var table = new LinkedHashMap<String, Object>();
            boolean correctness = outcome.equals("correctness");
            table.put("key", outcome); table.put("title", correctness ? "Correctness" : "Model safety ratings");
            table.put("method", correctness ? "Binomial GEE · bias-reduced cluster covariance · Wald χ²" : "Repeated-measures ANOVA · Greenhouse–Geisser correction · F");
            var modelRows = new ArrayList<Map<String, Object>>();
            for (var model : EvaluationView.MODELS) {
                var selected = rows.stream().filter(r -> model.key().equals(r.get("model"))).toList();
                int complete = completeBases(rows, model.key(),correctness);
                String reason = selected.isEmpty() ? "No responses for this model in the selected run."
                    : correctness && selected.stream().noneMatch(r->r.get("human_label")!=null) ? "No recognized gold.label human references for these prompts."
                    : !runtimeError.isEmpty() ? runtimeError
                    : "Needs 30 complete base queries; " + complete + " available. Each needs all 12 conditions.";
                var row = new LinkedHashMap<String, Object>();
                row.put("key", model.key()); row.put("name", model.name()); row.put("complete_bases", complete);
                row.put("included_prompts", complete * 12); row.put("saved_items", selected.size());
                row.put("excluded_items", selected.size() - complete * 12);
                row.put("reason", reason); row.put("descriptives", List.of());
                row.put("effects", EFFECTS.stream().map(name -> {
                    var effect = new LinkedHashMap<String, Object>(); effect.put("name", name);
                    effect.put("p", null); effect.put("adjusted_p", null); effect.put("reason", reason); return effect;
                }).toList());
                modelRows.add(row);
            }
            table.put("rows", modelRows); tables.add(table);
        }
        report.put("tables", tables);
        return report;
    }

    @SuppressWarnings("unchecked")
    private void decorate(Map<String, Object> report) {
        for (var table : (List<Map<String, Object>>) report.get("tables")) {
            for (var row : (List<Map<String, Object>>) table.get("rows")) {
                for (var e : (List<Map<String, Object>>) row.get("effects")) {
                    Double p = e.get("adjusted_p") instanceof Number n ? n.doubleValue() : null;
                    e.put("display", p == null ? "N/A" : p < .001 ? "< .001" : String.format(Locale.ROOT, "%.3f", p));
                    e.put("significant", p != null && p < .05);
                    for (String optional : List.of("statistic", "df", "df_error", "epsilon", "partial_eta_squared", "residual_shapiro_min_p"))
                        e.putIfAbsent(optional, null);
                }
            }
        }
    }
}
