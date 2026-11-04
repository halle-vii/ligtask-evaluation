package ph.ligtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StatisticalAnalysisTest {
    private final UUID dataset = UUID.randomUUID(), run = UUID.randomUUID();

    @Test void unavailableRuntimeDoesNotHideSmallSampleOrInventLabels() {
        var service = new StatisticalAnalysis(new ObjectMapper(), "/does-not-exist/python");
        UUID id = UUID.randomUUID();
        var prompt = Map.<String,Object>of("id",id,"instance_id","SYNTHETIC","base_query_id","BASE","context_condition","SAFE","language_variant","EN","task_type","NLU");
        var item = Map.<String,Object>of("prompt_id",id,"model_key","gpt-4o","status","VALID","safety_rating",8,"classification","Safe");
        var result = service.assess(dataset, run, List.of(prompt), List.of(item));
        var tree = new ObjectMapper().valueToTree(result);
        assertThat(tree.path("tables").get(0).path("rows").get(0).path("reason").asText()).contains("gold.label");
        assertThat(tree.path("tables").get(1).path("rows").get(0).path("reason").asText()).contains("0 available");
        assertThat(tree.path("tables").get(1).path("rows").get(0).path("effects").get(0).path("display").asText()).isEqualTo("N/A");
    }

    @Test void foreignPromptCannotEnterAnalysis() {
        var service = new StatisticalAnalysis(new ObjectMapper(), "/does-not-exist/python");
        assertThatThrownBy(() -> service.assess(dataset,run,List.of(),List.of(Map.of("prompt_id",UUID.randomUUID()))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void javaToPythonBridgeComputesCorrectnessFromConfirmedGoldAndRatingEffects() {
        Path python=Path.of(".local/analysis-venv/bin/python");
        Assumptions.assumeTrue(Files.isExecutable(python), "Install local analysis dependencies with sh scripts/setup-analysis");
        var prompts = new ArrayList<Map<String,Object>>();
        var items = new ArrayList<Map<String,Object>>();
        var random = new Random(8342);
        for(int base=0;base<32;base++) {
            int cell=0;
            for(String context:List.of("SAFE","UNSAFE")) for(String language:List.of("EN","FIL")) for(String task:List.of("NLU","NLR","NLG")) {
                UUID id=UUID.randomUUID();
                prompts.add(Map.of("id",id,"instance_id","SYNTHETIC_"+base+"_"+cell,"base_query_id","SYNTHETIC_"+base,"context_condition",context,"language_variant",language,"task_type",task,
                    "raw_record",Map.of("source_task",Map.of("gold",Map.of("label",ph.ligtask.llm.LigtaskOutputContract.labels(task).get(random.nextBoolean()?0:1))))));
                items.add(Map.of("prompt_id",id,"model_key","gpt-4o","status","VALID","safety_rating",1+((base*7+cell*3+(base*cell)%13)%10),"classification",ph.ligtask.llm.LigtaskOutputContract.labels(task).get(0)));
                cell++;
            }
        }
        var service = new StatisticalAnalysis(new ObjectMapper(),python.toAbsolutePath().toString());
        var tree = new ObjectMapper().valueToTree(service.assess(dataset,run,prompts,items));
        assertThat(tree.path("libraries").path("statsmodels").asText()).isEqualTo("0.14.6");
        assertThat(tree.path("tables").get(1).path("rows").get(0).path("complete_bases").asInt()).isEqualTo(32);
        assertThat(tree.path("tables").get(1).path("rows").get(0).path("effects").get(0).path("p").isNumber()).isTrue();
        assertThat(tree.path("tables").get(0).path("rows").get(0).path("complete_bases").asInt()).isEqualTo(32);
        assertThat(tree.path("tables").get(0).path("rows").get(0).path("effects").get(0).path("p").isNumber()).isTrue();
    }
}
