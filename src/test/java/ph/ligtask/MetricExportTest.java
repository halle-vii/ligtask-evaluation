package ph.ligtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ph.ligtask.runs.RunStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetricExportTest {
    final DatasetStore datasets=mock(DatasetStore.class);
    final RunStore runs=mock(RunStore.class);
    final ObjectMapper json=new ObjectMapper();
    final MetricExport exporter=new MetricExport(datasets,runs,json);
    final UUID dataset=UUID.randomUUID();
    final EvaluationView.Filters all=new EvaluationView.Filters("","","","",0);

    Map<String,Object> prompt(UUID id,String instance,String task,String language,String context){
        return Map.of("id",id,"instance_id",instance,"base_query_id","BASE","task_type",task,"language_variant",language,"context_condition",context,
            "prepared_prompt_text","SECRET QUERY","raw_record",Map.of("note","SECRET ANNOTATION"),"context_text","SECRET CONTEXT");
    }
    void prepare(List<Map<String,Object>> prompts){
        when(datasets.dataset(dataset)).thenReturn(Map.of("id",dataset,"version","synthetic-v1","synthetic",true));
        when(datasets.records(dataset)).thenReturn(prompts);
    }
    String[] row(MetricExport.Export data,int index){return new String(data.csv(),StandardCharsets.UTF_8).lines().skip(index+1).findFirst().orElseThrow().split(",",-1);}

    @Test void allSevenModelsAreExportedNumericallyWithoutInventingMissingData() throws Exception {
        UUID id=UUID.randomUUID();prepare(List.of(prompt(id,"BASE_SAFE_NLG_EN","NLG","EN","SAFE")));
        var data=exporter.prepare(dataset,all,null);
        assertThat(new String(data.csv(),StandardCharsets.UTF_8).lines().count()).isEqualTo(8);
        for(int i=0;i<7;i++){
            var values=row(data,i);assertThat(values).hasSize(14);assertThat(values[2]).isEqualTo(Integer.toString(i+1));
            assertThat(values[6]).isEqualTo("0");assertThat(values[7]).isEqualTo("0");
            for(int col=9;col<14;col++)assertThat(values[col]).isEmpty();
            for(var value:values)if(!value.isEmpty())assertThatCode(()->Double.parseDouble(value)).doesNotThrowAnyException();
        }
        var files=new LinkedHashMap<String,byte[]>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(exporter.zip(data)))){
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;)files.put(entry.getName(),zip.readAllBytes());
        }
        assertThat(files.keySet()).hasSize(10).contains("metrics.csv","manifest.json","README.txt");
        assertThat(files.keySet().stream().filter(n->n.startsWith("models/")).count()).isEqualTo(7);
        assertThat(files.keySet()).noneMatch(n->n.endsWith(".ipynb")||n.endsWith(".py"));
        assertThat(new String(data.csv(),StandardCharsets.UTF_8)).doesNotContain("SECRET","BASE_SAFE_NLG_EN");
        assertThat(json.writeValueAsString(data.manifest())).doesNotContain("SECRET QUERY","SECRET CONTEXT","SECRET ANNOTATION");
        verify(runs,never()).metricRows(any(),any());
    }

    @Test void savedResultsAreSeparatedByModelAndUseOneRunPerModel() {
        UUID id=UUID.randomUUID(),gptRun=UUID.randomUUID(),claudeRun=UUID.randomUUID();
        prepare(List.of(prompt(id,"BASE_SAFE_NLG_EN","NLG","EN","SAFE")));
        when(runs.latestFinishedForModel(dataset,"gpt-4o")).thenReturn(Optional.of(Map.of("id",gptRun,"status","COMPLETED")));
        when(runs.latestFinishedForModel(dataset,"claude-sonnet-4-5")).thenReturn(Optional.of(Map.of("id",claudeRun,"status","COMPLETED")));
        when(runs.metricRows(gptRun,"gpt-4o")).thenReturn(List.of(Map.of("prompt_id",id,"status","VALID","classification","Answer","safety_rating",8,"positive_probability",0.15,"reused",true,"model_id","provider-gpt")));
        when(runs.metricRows(claudeRun,"claude-sonnet-4-5")).thenReturn(List.of(Map.of("prompt_id",id,"status","VALID","classification","Refuse","safety_rating",3,"model_id","provider-claude")));
        var data=exporter.prepare(dataset,all,null);
        assertThat(row(data,0)[10]).isEqualTo("0");assertThat(row(data,0)[12]).isEqualTo("8.0");assertThat(row(data,0)[8]).isEqualTo("1");
        assertThat(row(data,1)[10]).isEqualTo("1");assertThat(row(data,1)[12]).isEqualTo("3.0");
        assertThat(row(data,0)[9]).isEmpty();assertThat(row(data,0)[11]).isEmpty();assertThat(row(data,0)[13]).isEqualTo("0.15");
        assertThat(row(data,1)[13]).isEmpty(); // Legacy saved result without probabilities.
        var manifest=json.valueToTree(data.manifest());
        assertThat(manifest.path("models").get(0).path("run_id").asText()).isEqualTo(gptRun.toString());
        assertThat(manifest.path("models").get(1).path("run_id").asText()).isEqualTo(claudeRun.toString());
    }

    @Test void filtersCoverEveryPageAndPreserveStableNumericIndexes() {
        var prompts=new ArrayList<Map<String,Object>>();
        for(int i=0;i<30;i++)prompts.add(prompt(UUID.randomUUID(),"BASE_"+i,"NLU",i<20?"EN":"FIL","SAFE"));
        prepare(prompts);
        var data=exporter.prepare(dataset,new EvaluationView.Filters("NLU","EN","SAFE","",9),null);
        assertThat(data.manifest().get("matching_prompts")).isEqualTo(20);
        assertThat(new String(data.csv(),StandardCharsets.UTF_8).lines().count()).isEqualTo(141);
        var filipino=exporter.prepare(dataset,new EvaluationView.Filters("NLU","FIL","SAFE","",0),null);
        assertThat(row(filipino,0)[0]).isEqualTo("21");
    }

    @Test void explicitRunDoesNotMixOlderResultsAndActiveOrForeignRunsAreRejected() {
        UUID id=UUID.randomUUID(),run=UUID.randomUUID();prepare(List.of(prompt(id,"BASE","NLU","EN","SAFE")));
        when(runs.run(dataset,run)).thenReturn(Map.of("id",run,"status","COMPLETED"));
        exporter.prepare(dataset,all,run);
        verify(runs,never()).latestFinishedForModel(any(),any());
        verify(runs,times(7)).metricRows(eq(run),any());
        when(runs.run(dataset,run)).thenReturn(Map.of("id",run,"status","RUNNING"));
        assertThatThrownBy(()->exporter.prepare(dataset,all,run)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        when(runs.run(dataset,run)).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertThatThrownBy(()->exporter.prepare(dataset,all,run)).isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
    }

    @Test void invalidResponsesKeepTheirStatusWithoutNumericJudgments() {
        UUID id=UUID.randomUUID(),run=UUID.randomUUID();prepare(List.of(prompt(id,"BASE","NLU","EN","UNKNOWN")));
        when(runs.latestFinishedForModel(dataset,"gpt-4o")).thenReturn(Optional.of(Map.of("id",run,"status","COMPLETED_WITH_ERRORS")));
        when(runs.metricRows(run,"gpt-4o")).thenReturn(List.of(Map.of("prompt_id",id,"status","INVALID_RESPONSE","classification","Safe","safety_rating",8)));
        var values=row(exporter.prepare(dataset,all,null),0);
        assertThat(values[5]).isEqualTo("2");assertThat(values[6]).isEqualTo("2");assertThat(values[7]).isEqualTo("0");
        assertThat(values[10]).isEmpty();assertThat(values[12]).isEmpty();
    }
    @Test void exportsConfirmedGoldLabelForEveryModelEvenWithoutSavedResponses() {
        UUID id=UUID.randomUUID();var p=new HashMap<>(prompt(id,"BASE","NLG","EN","UNSAFE"));
        p.put("raw_record",Map.of("source_task",Map.of("gold",Map.of("label","Answer","binary_rating",1,"rating_mean",9))));
        prepare(List.of(p));var data=exporter.prepare(dataset,all,null);
        for(int i=0;i<7;i++) {
            assertThat(row(data,i)[9]).isEqualTo("0");
            assertThat(row(data,i)[11]).isEqualTo("9.0");
            assertThat(row(data,i)[13]).isEmpty();
        }
    }
}
