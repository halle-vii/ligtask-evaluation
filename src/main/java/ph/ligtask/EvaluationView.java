package ph.ligtask;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Read-only presentation of prepared inputs. No evaluation is performed here. */
public final class EvaluationView {
    private EvaluationView() {}

    public record ModelChoice(String key, String name) {
        public String logo() {
            String icon = switch (key) {
                case "gpt-4o", "gpt-4o-mini" -> "openai";
                case "claude-3-5-sonnet" -> "claude-color";
                case "llama-3-70b-instruct" -> "meta-color";
                case "qwen2-72b" -> "qwen-color";
                case "mixtral-8x7b" -> "mistral-color";
                case "dolphin-2-9-llama3-70b" -> "dolphin";
                default -> throw new IllegalStateException("Missing model logo: " + key);
            };
            return "/assets/models/" + icon + ".svg";
        }
    }
    // Design reference labels, not configured providers or claims of model availability.
    public static final List<ModelChoice> MODELS = List.of(
        new ModelChoice("gpt-4o", "GPT-4o"),
        new ModelChoice("claude-3-5-sonnet", "Claude-3.5-sonnet"),
        new ModelChoice("llama-3-70b-instruct", "Llama-3-70B-Instruct"),
        new ModelChoice("qwen2-72b", "Qwen2-72B"),
        new ModelChoice("mixtral-8x7b", "Mixtral 8x7B"),
        new ModelChoice("dolphin-2-9-llama3-70b", "Dolphin-2.9-Llama3-70B"),
        new ModelChoice("gpt-4o-mini", "GPT-4o-mini"));
    public static final List<String> METRICS = List.of("Accuracy", "Precision", "Recall", "F1 score", "PCC", "BCE");
    public static final int PAGE_SIZE = 12;

    public static ModelChoice model(String key) {
        return MODELS.stream().filter(m -> m.key().equals(key)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown model view"));
    }

    public record Filters(String task, String language, String context, String q, int page) {
        public Filters {
            task = valid(task, Set.of("", "NLU", "NLR", "NLG"));
            language = valid(language, Set.of("", "EN", "FIL"));
            context = valid(context, Set.of("", "SAFE", "UNSAFE", "UNKNOWN"));
            q = q == null ? "" : q.strip();
            if (q.length() > 200 || page < 0 || page > 100000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search or page");
            }
        }
        private static String valid(String value, Set<String> allowed) {
            value = value == null ? "" : value;
            if (!allowed.contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filter");
            return value;
        }
        boolean matches(Map<String, Object> row) {
            if (!task.isEmpty() && !task.equals(row.get("task_type"))) return false;
            if (!language.isEmpty() && !language.equals(row.get("language_variant"))) return false;
            if (!context.isEmpty() && !context.equals(row.get("context_condition"))) return false;
            String needle = q.toLowerCase(Locale.ROOT);
            return List.of("instance_id", "upstream_entry_id", "base_query_id", "context_id", "prepared_prompt_text", "context_text", "policy_text")
                .stream().anyMatch(field -> Objects.toString(row.get(field), "").toLowerCase(Locale.ROOT).contains(needle));
        }
        public Filters atPage(int selected) { return new Filters(task, language, context, q, selected); }
    }

    public record Page(List<Map<String, Object>> rows, List<Map<String, Object>> matching,
                       int total, int count, int number, int pages, int start, int end) {}

    public static Page filter(List<Map<String, Object>> all, Filters filters) {
        var matching = all.stream().filter(filters::matches).toList();
        int pages = Math.max(1, (matching.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int number = Math.min(filters.page(), pages - 1);
        int from = number * PAGE_SIZE, to = Math.min(from + PAGE_SIZE, matching.size());
        return new Page(matching.subList(from, to), matching, all.size(), matching.size(), number, pages,
            matching.isEmpty() ? 0 : from + 1, to);
    }
}
