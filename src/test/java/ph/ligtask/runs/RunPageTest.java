package ph.ligtask.runs;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ph.ligtask.*;
import ph.ligtask.llm.*;
import org.springframework.context.annotation.Import;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@WebMvcTest(EvaluationController.class) @WithMockUser
@Import({StatisticalAnalysis.class,MetricExport.class})
class RunPageTest {
    @Autowired MockMvc mvc;
    @MockitoBean DatasetStore datasets;@MockitoBean ModelConnectionStatusService statuses;
    @MockitoBean LigtaskPromptBuilderService builder;@MockitoBean RunCoordinator coordinator;@MockitoBean RunStore runs;
    final UUID dataset=UUID.randomUUID();
    @BeforeEach void setup(){
        var meta=new HashMap<String,Object>();meta.put("id",dataset);meta.put("name","Synthetic dataset");meta.put("synthetic",true);meta.put("archived_at",null);
        when(datasets.dataset(dataset)).thenReturn(meta);when(datasets.all()).thenReturn(List.of(meta));
        when(datasets.records(dataset)).thenReturn(List.of());
        var availability=new HashMap<String,Boolean>();for(var m:EvaluationView.MODELS)availability.put(m.key(),m.key().equals("gpt-4o"));
        when(coordinator.availability()).thenReturn(availability);
    }
    @Test void runsTemplateRendersCostAcknowledgementScopeAndProgressWithoutSendingRequests() throws Exception {
        String html=mvc.perform(get("/evaluation/"+dataset+"/runs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("One prompt (test)","run-cost-ack","provider charges","data-run-api=\"/evaluation/"+dataset+"/runs/api\"","data-csrf-token=","Saved runs","API key configured","API key missing","Stop after current request");
        assertThat(html).doesNotContain("Execution not connected");
        verify(coordinator,never()).start(any(),any());verifyNoInteractions(builder);
    }
    @Test void metricCardsExplainMissingInputsAndShowManuscriptFormulasWithoutProviderCalls() throws Exception {
        String html=mvc.perform(get("/evaluation/"+dataset+"/models/gpt-4o"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Metric formulas and requirements", "Accuracy", "Precision", "Recall", "F1 score", "PCC", "BCE",
            "N/A", "finalized human", "TP / (TP + FP)", "not a probability", "printed pages 82–85");
        verifyNoInteractions(builder); verify(coordinator,never()).start(any(),any());
    }
    @Test void analysisPageHasSidebarAndTwoRealSevenModelTablesWithoutStartingRuns() throws Exception {
        String html=mvc.perform(get("/evaluation/"+dataset+"/analysis"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Statistical analysis", "aria-current=\"page\"", "Table 1. Correctness", "Table 2. Model safety ratings",
            "No finished runs available", "Repeated-measures ANOVA", "Binomial GEE", "Holm", "C × L × T", "N/A");
        for(var choice:EvaluationView.MODELS) assertThat(html).contains(choice.name());
        verifyNoInteractions(builder); verify(coordinator,never()).start(any(),any());
    }
    @Test void analysisIsScopedToOneFinishedRunAndCanBeExported() throws Exception {
        UUID run=UUID.randomUUID(),prompt=UUID.randomUUID();
        var savedRun=Map.<String,Object>of("id",run,"status","COMPLETED","created_at","2026-11-04T12:00:00Z");
        when(runs.history(dataset)).thenReturn(List.of(savedRun));when(runs.run(dataset,run)).thenReturn(savedRun);
        when(datasets.records(dataset)).thenReturn(List.of(Map.of("id",prompt,"instance_id","BASE_SAFE_NLU_EN","base_query_id","BASE","context_condition","SAFE","language_variant","EN","task_type","NLU")));
        when(runs.export(run)).thenReturn(List.of(Map.of("prompt_id",prompt,"model_key","gpt-4o","status","VALID","classification","Safe","safety_rating",8)));
        mvc.perform(get("/evaluation/"+dataset+"/analysis.json").param("run",run.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run_id").value(run.toString()))
            .andExpect(jsonPath("$.tables[1].rows[0].complete_bases").value(0))
            .andExpect(jsonPath("$.tables[0].rows[0].effects[0].p").isEmpty());
        verify(runs).export(run);verify(coordinator,never()).start(any(),any());
    }
    @Test void analysisRejectsForeignAndActiveRuns() throws Exception {
        UUID foreign=UUID.randomUUID(),active=UUID.randomUUID();
        when(runs.run(dataset,foreign)).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        when(runs.run(dataset,active)).thenReturn(Map.of("status","RUNNING"));
        mvc.perform(get("/evaluation/"+dataset+"/analysis").param("run",foreign.toString())).andExpect(status().isNotFound());
        mvc.perform(get("/evaluation/"+dataset+"/analysis").param("run",active.toString())).andExpect(status().isConflict());
        verify(runs,never()).export(any());verify(coordinator,never()).start(any(),any());
    }
    @Test void metricExportDialogIncludesAllModelsAndNumericEndpointsWithoutProviderCalls() throws Exception {
        String html=mvc.perform(get("/evaluation/"+dataset+"/models/gpt-4o"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Export metrics","Export metric data","all seven models","Download model CSVs (ZIP)","Download combined CSV");
        mvc.perform(get("/evaluation/"+dataset+"/metrics.csv"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=\"ligtask-all-models-metrics.csv\""))
            .andExpect(content().string(MetricExport.HEADER+"\r\n"));
        mvc.perform(get("/evaluation/"+dataset+"/metrics.zip")).andExpect(status().isOk()).andExpect(content().contentType("application/zip"));
        mvc.perform(get("/evaluation/"+dataset+"/metrics-manifest.json")).andExpect(status().isOk())
            .andExpect(jsonPath("$.models.length()").value(7));
        verify(coordinator,never()).start(any(),any());verifyNoInteractions(builder);
    }
    @Test void goldLabelPopulatesPreparedPromptsAndSavedModelMetricsWithoutPaidRequests() throws Exception {
        UUID prompt=UUID.randomUUID(),run=UUID.randomUUID();
        var row=Map.<String,Object>of("id",prompt,"instance_id","SYNTHETIC_SAFE_NLG_EN","base_query_id","SYNTHETIC","context_condition","SAFE","task_type","NLG","language_variant","EN","prepared_prompt_text","Synthetic query",
            "raw_record","{}");
        var stored=new HashMap<>(row);stored.put("raw_record",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("source_task",Map.of("gold",Map.of("label","Answer","rating_mean",8)))));
        when(datasets.records(dataset)).thenReturn(List.of(stored));
        when(runs.latestFinishedForModel(dataset,"gpt-4o")).thenReturn(Optional.of(Map.of("id",run,"status","COMPLETED","created_at","2026-11-04T12:00:00Z")));
        when(runs.metricRows(run,"gpt-4o")).thenReturn(List.of(Map.of("prompt_id",prompt,"status","VALID","classification","Answer","safety_rating",8,"positive_probability",0.2)));
        String prepared=mvc.perform(get("/evaluation/"+dataset+"/prompts")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(prepared).contains("Human reference from gold.label",">Answer</span>");
        var result=mvc.perform(get("/evaluation/"+dataset+"/models/gpt-4o")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("100.00%",">Match</span>");
        var metrics=(EvaluationMetrics.Report)result.getModelAndView().getModel().get("metricReport");
        assertThat(metrics.confusion().tn()).isEqualTo(1);assertThat(metrics.metrics().get(0).count()).isEqualTo(1);
        assertThat(metrics.metrics().get(4).value()).isNull();
        assertThat(metrics.metrics().get(5).value()).isCloseTo(-Math.log(0.8),within(1e-12));
        assertThat(metrics.metrics().get(5).count()).isEqualTo(1);
        assertThat(metrics.metrics().get(4).count()).isEqualTo(1);
        mvc.perform(get("/evaluation/"+dataset)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("100.00%")));
        verify(coordinator,never()).start(any(),any());verifyNoInteractions(builder);
    }
    @Test void pccUsesPairedHumanMeansAndSavedModelRatingsWithoutLabelOrProbabilityInference() throws Exception {
        UUID run=UUID.randomUUID();
        var prompts=new ArrayList<Map<String,Object>>();var responses=new ArrayList<Map<String,Object>>();
        double[] human={2.5,5.0,7.5};int[] model={3,5,7};
        for(int i=0;i<3;i++) {
            UUID id=UUID.randomUUID();
            prompts.add(Map.of("id",id,"instance_id","SYNTHETIC_"+i,"base_query_id","BASE_"+i,"context_condition","SAFE","task_type","NLU","language_variant","EN","prepared_prompt_text","Synthetic query",
                "raw_record",Map.of("source_task",Map.of("gold",Map.of("rating_mean",human[i])))));
            responses.add(Map.of("prompt_id",id,"status","VALID","classification","Safe","safety_rating",model[i]));
        }
        when(datasets.records(dataset)).thenReturn(prompts);
        when(runs.latestFinishedForModel(dataset,"gpt-4o")).thenReturn(Optional.of(Map.of("id",run,"status","COMPLETED","created_at","2026-11-04T12:00:00Z")));
        when(runs.metricRows(run,"gpt-4o")).thenReturn(responses);
        var result=mvc.perform(get("/evaluation/"+dataset+"/models/gpt-4o")).andExpect(status().isOk()).andReturn();
        var metrics=(EvaluationMetrics.Report)result.getModelAndView().getModel().get("metricReport");
        assertThat(metrics.metrics().get(4).value()).isCloseTo(1.0,within(1e-12));
        assertThat(metrics.metrics().get(4).count()).isEqualTo(3);
        assertThat(metrics.metrics().get(0).value()).isNull();assertThat(metrics.metrics().get(5).value()).isNull();
        verify(coordinator,never()).start(any(),any());verifyNoInteractions(builder);
    }
    @Test void historicalRunSelectionPersistsThroughFiltersPaginationAndPromptDetails() throws Exception {
        UUID older=UUID.randomUUID(),latest=UUID.randomUUID(),prompt=UUID.randomUUID();
        var saved=Map.<String,Object>of("id",older,"status","COMPLETED","created_at","2026-11-01T12:00:00Z");
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<13;i++)rows.add(Map.of("id",i==0?prompt:UUID.randomUUID(),"instance_id","SYNTHETIC_"+i,"base_query_id","BASE_"+i,"context_condition","SAFE","task_type","NLU","language_variant","EN","prepared_prompt_text","Synthetic query",
            "raw_record","{\"source_task\":{\"gold\":{\"label\":\"Safe\",\"rating_mean\":8}}}"));
        for(int i=0;i<rows.size();i++) {
            var complete=new HashMap<>(rows.get(i));
            complete.put("upstream_entry_id","SOURCE_"+i);complete.put("context_id","CONTEXT_"+i);
            complete.put("context_text",null);complete.put("policy_text",null);rows.set(i,complete);
        }
        when(datasets.records(dataset)).thenReturn(rows);
        when(runs.run(dataset,older)).thenReturn(saved);
        when(runs.finished(dataset,"gpt-4o")).thenReturn(List.of(saved));
        when(runs.latestFinishedForModel(dataset,"gpt-4o")).thenReturn(Optional.of(Map.of("id",latest,"status","COMPLETED")));
        when(runs.metricRows(older,"gpt-4o")).thenReturn(List.of(Map.of("prompt_id",prompt,"status","VALID","classification","Safe","safety_rating",8)));
        when(runs.metricRows(latest,"gpt-4o")).thenReturn(List.of(Map.of("prompt_id",prompt,"status","VALID","classification","Unsafe","safety_rating",2)));
        var result=mvc.perform(get("/evaluation/"+dataset+"/models/gpt-4o").param("run",older.toString()).param("language","EN")).andExpect(status().isOk()).andReturn();
        var model=result.getModelAndView().getModel();
        assertThat(model.get("selectedRunId")).isEqualTo(older);
        assertThat(((EvaluationMetrics.Report)model.get("metricReport")).confusion().tn()).isEqualTo(1);
        assertThat(model.get("nextUrl").toString()).contains("run="+older,"language=EN");
        assertThat(((Map<?,?>)model.get("promptLinks")).get(prompt).toString()).contains("run="+older,"model=gpt-4o");
        assertThat(result.getResponse().getContentAsString()).contains("name=\"run\"",">Match</span>");
        var detail=new HashMap<String,Object>();detail.put("status","VALID");detail.put("classification","Safe");detail.put("safety_rating",8);detail.put("positive_probability",0.15);detail.put("explanation","Saved synthetic explanation");detail.put("error_detail",null);detail.put("response",Map.of("responseText","ORIGINAL SAVED RESPONSE"));
        when(runs.promptResult(older,prompt,"gpt-4o")).thenReturn(Optional.of(detail));
        var page=mvc.perform(get("/evaluation/"+dataset+"/prompts/"+prompt).param("model","gpt-4o").param("run",older.toString())).andExpect(status().isOk()).andReturn();
        assertThat(page.getResponse().getContentAsString()).contains("Saved synthetic explanation","ORIGINAL SAVED RESPONSE",">Match</dd>","0.15");
        assertThat(page.getModelAndView().getModel().get("nextPrompt").toString()).contains("run="+older);
        when(runs.promptResult(older,prompt,"gpt-4o")).thenReturn(Optional.empty());
        mvc.perform(get("/evaluation/"+dataset+"/prompts/"+prompt).param("model","gpt-4o").param("run",older.toString())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Not evaluated in this run")));
        verify(coordinator,never()).start(any(),any());verifyNoInteractions(builder);
    }
    @Test void resultsAndPromptViewsRejectForeignAndActiveRuns() throws Exception {
        UUID foreign=UUID.randomUUID(),active=UUID.randomUUID(),prompt=UUID.randomUUID();
        when(runs.run(dataset,foreign)).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        when(runs.run(dataset,active)).thenReturn(Map.of("status","RUNNING"));
        for(String path:List.of("/evaluation/"+dataset,"/evaluation/"+dataset+"/models/gpt-4o","/evaluation/"+dataset+"/prompts/"+prompt+"?model=gpt-4o")) {
            mvc.perform(get(path).param("run",foreign.toString())).andExpect(status().isNotFound());
            mvc.perform(get(path).param("run",active.toString())).andExpect(status().isConflict());
        }
        verify(runs,never()).metricRows(any(),any());verify(runs,never()).promptResult(any(),any(),any());
    }
}
