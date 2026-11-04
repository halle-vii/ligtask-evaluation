package ph.ligtask.runs;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResourceAccessException;
import com.fasterxml.jackson.databind.*;
import ph.ligtask.*;
import ph.ligtask.llm.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://127.0.0.1:55432/ligtask_test}","ligtask.runs.worker-enabled=false"})
class RunPersistenceTest {
    @Autowired DatasetStore datasets;@Autowired DatasetImporter importer;@Autowired RunStore store;@Autowired JdbcTemplate db;@Autowired DataSource source;@Autowired ObjectMapper json;@Autowired MetricExport metricExport;
    UUID dataset;FakeProvider fake;RunCoordinator coordinator;
    static class FakeProvider implements LlmProvider {
        int calls;Runnable action=()->{};
        public ModelConfiguration.Provider provider(){return ModelConfiguration.Provider.OPENROUTER;}
        public LlmResponse generate(LlmRequest req){calls++;action.run();return new LlmResponse(req.modelId(),"{\"classification\":\"Answer\",\"explanation\":\"Synthetic fixture\",\"safety_rating\":8,\"positive_probability\":0.2}",null,"stop","synthetic-request",10,20);}
    }
    @BeforeEach void setup() throws Exception {
        if(!db.queryForObject("select current_database()",String.class).endsWith("_test"))throw new IllegalStateException("Only a dedicated _test database is allowed");
        db.execute("truncate evaluation_run_item,evaluation_response,evaluation_run,prompt_instance,dataset_version");
        byte[] bytes=Files.readAllBytes(Path.of("src/main/resources/static/samples/synthetic-tfg.json"));dataset=datasets.save(importer.parse(bytes),bytes,"synthetic.json");
        fake=new FakeProvider();var providers=new LlmProviderService(List.of(fake));var builder=new LigtaskPromptBuilderService(datasets,new LigtaskPromptTemplate());
        coordinator=new RunCoordinator(datasets,store,builder,new LigtaskEvaluationService(builder,providers),providers,json,source,false,1000){@Override protected void pause(){}};
    }
    RunCoordinator.Start plan(UUID token,int count){return new RunCoordinator.Start(token,List.of("gpt-4o"),count,"",512,true);}
    @Test void realDatabaseSavesRunResponseAndReusesItAcrossRuns(){
        UUID token=UUID.randomUUID();UUID first=coordinator.start(dataset,plan(token,1));
        assertThat(coordinator.start(dataset,plan(token,1))).isEqualTo(first);
        assertThat(fake.calls).isZero();coordinator.execute(dataset,first);
        assertThat(store.run(dataset,first).get("status")).isEqualTo("COMPLETED");
        assertThat(store.results(first,0).get(0).get("classification")).isEqualTo("Answer");
        assertThat(store.results(first,0).get(0).get("response").toString()).contains("synthetic-request");
        UUID second=coordinator.start(dataset,plan(UUID.randomUUID(),1));coordinator.execute(dataset,second);
        assertThat(fake.calls).isEqualTo(1);assertThat(store.results(second,0).get(0).get("reused")).isEqualTo(true);
        assertThat(db.queryForObject("select count(*) from evaluation_response",Integer.class)).isEqualTo(1);
        assertThat(store.latestFinishedForModel(dataset,"gpt-4o").orElseThrow().get("id")).isEqualTo(second);
        assertThat(store.latestFinishedForModel(dataset,"claude-sonnet-4-5")).isEmpty();
        var numeric=store.metricRows(second,"gpt-4o").get(0);
        assertThat(numeric.get("classification")).isEqualTo("Answer");
        assertThat(numeric.get("safety_rating")).isEqualTo(8);
        assertThat(((Number)numeric.get("positive_probability")).doubleValue()).isEqualTo(0.2);
        assertThat(numeric).doesNotContainKeys("response","input_snapshot","explanation");
        var exported=metricExport.prepare(dataset,new EvaluationView.Filters("","","","",0),null);
        assertThat(exported.perModel()).hasSize(7);
        assertThat(new String(exported.csv(),java.nio.charset.StandardCharsets.UTF_8).lines().count()).isEqualTo(1+7*datasets.records(dataset).size());
        coordinator.start(dataset,plan(UUID.randomUUID(),1));
        assertThat(store.latestFinishedForModel(dataset,"gpt-4o").orElseThrow().get("id")).isEqualTo(second);
    }
    @Test void changingOutputLimitCreatesANewFingerprintButSameSettingsDoNot(){
        UUID first=coordinator.start(dataset,plan(UUID.randomUUID(),1));coordinator.execute(dataset,first);
        UUID second=coordinator.start(dataset,new RunCoordinator.Start(UUID.randomUUID(),List.of("gpt-4o"),1,"",256,true));coordinator.execute(dataset,second);
        assertThat(fake.calls).isEqualTo(2);
    }
    @Test void cancellationSavesTheInFlightResultAndCancelsRemainingItems(){
        UUID run=coordinator.start(dataset,plan(UUID.randomUUID(),12));fake.action=()->store.cancel(run);coordinator.execute(dataset,run);
        assertThat(fake.calls).isEqualTo(1);assertThat(store.run(dataset,run).get("status")).isEqualTo("CANCELLED");
        assertThat(store.items(run).stream().filter(i->i.get("status").equals("VALID")).count()).isEqualTo(1);
        assertThat(store.items(run).stream().filter(i->i.get("status").equals("CANCELLED")).count()).isEqualTo(11);
    }
    @Test void interruptedAttemptIsNeverReissuedAfterRecovery(){
        UUID run=coordinator.start(dataset,plan(UUID.randomUUID(),1));store.begin(run);var item=store.items(run).get(0);
        var claim=store.claim(dataset,item,Map.of("maxTokens",512));store.attach((UUID)item.get("id"),(UUID)claim.get("id"),"IN_FLIGHT",false);
        store.recover();assertThat(store.run(dataset,run).get("status")).isEqualTo("INTERRUPTED");
        UUID next=coordinator.start(dataset,plan(UUID.randomUUID(),1));coordinator.execute(dataset,next);
        assertThat(fake.calls).isZero();assertThat(store.results(next,0).get(0).get("status")).isEqualTo("UNCERTAIN");
    }
    @Test void activeRunConstraintPreventsConcurrentStarts(){
        coordinator.start(dataset,plan(UUID.randomUUID(),1));
        assertThatThrownBy(()->coordinator.start(dataset,plan(UUID.randomUUID(),1))).hasMessageContaining("Another run is active");
        assertThat(db.queryForObject("select count(*) from evaluation_run",Integer.class)).isEqualTo(1);
    }
    @Test void providerTimeoutLeavesAnAuditableUncertainAttempt(){
        UUID run=coordinator.start(dataset,plan(UUID.randomUUID(),12));fake.action=()->{throw new ResourceAccessException("Synthetic timeout");};
        coordinator.execute(dataset,run);assertThat(fake.calls).isEqualTo(1);
        assertThat(store.run(dataset,run).get("status")).isEqualTo("INTERRUPTED");
        var row=store.results(run,0).get(0);assertThat(row.get("status")).isEqualTo("UNCERTAIN");assertThat(row.get("classification")).isNull();
    }
    @Test void explicitRetryPreservesSuccessesAndUsesOriginalSnapshotsWithoutChangingSourceRun(){
        UUID original=coordinator.start(dataset,plan(UUID.randomUUID(),2));
        fake.action=()->store.cancel(original);coordinator.execute(dataset,original);
        assertThat(fake.calls).isEqualTo(1);
        var sourceItems=store.items(original);
        var preview=coordinator.retryPlan(dataset,original);
        assertThat(preview).containsEntry("preserved",1L).containsEntry("requests",1L);
        UUID token=UUID.randomUUID();var request=new RunCoordinator.Retry(token,true,false);
        UUID retry=coordinator.retry(dataset,original,request);
        assertThat(coordinator.retry(dataset,original,request)).isEqualTo(retry);
        assertThat(fake.calls).isEqualTo(1); // Planning/submission never invokes a provider with the worker disabled.
        assertThatThrownBy(()->store.delete(dataset,original)).hasMessageContaining("active run");
        fake.action=()->{};coordinator.execute(dataset,retry);
        assertThat(fake.calls).isEqualTo(2);
        assertThat(store.run(dataset,retry).get("status")).isEqualTo("COMPLETED");
        assertThat(((JsonNode)store.run(dataset,retry).get("settings")).path("retryOf").asText()).isEqualTo(original.toString());
        var retryItems=store.items(retry);
        assertThat(retryItems).hasSize(2);
        assertThat(retryItems.stream().filter(i->Boolean.TRUE.equals(i.get("reused"))).count()).isEqualTo(1);
        for(int i=0;i<2;i++)assertThat(retryItems.get(i).get("input_snapshot")).isEqualTo(sourceItems.get(i).get("input_snapshot"));
        assertThat(store.run(dataset,original).get("status")).isEqualTo("CANCELLED");
        assertThat(store.items(original).stream().filter(i->"CANCELLED".equals(i.get("status"))).count()).isEqualTo(1);
        assertThat(store.promptResult(retry,(UUID)retryItems.get(0).get("prompt_id"),"gpt-4o").orElseThrow().get("classification")).isEqualTo("Answer");
        var removed=store.delete(dataset,original);
        assertThat(removed).containsEntry("sharedResponsesRetained",1).containsEntry("deletedResponses",0);
        assertThat(store.results(retry,0)).hasSize(2);
        assertThat(store.finished(dataset,"gpt-4o")).hasSize(1);
        assertThat(store.delete(dataset,retry)).containsEntry("deletedResponses",2);
        assertThat(db.queryForObject("select count(*) from evaluation_response",Integer.class)).isZero();
        assertThat(datasets.records(dataset)).hasSize(12);
    }
    @Test void uncertainRetryRequiresSeparateAcknowledgementAndKeepsPriorAttemptAuditable(){
        UUID original=coordinator.start(dataset,plan(UUID.randomUUID(),1));
        fake.action=()->{throw new ResourceAccessException("Synthetic timeout");};coordinator.execute(dataset,original);
        assertThat(coordinator.retryPlan(dataset,original)).containsEntry("uncertain",1L);
        assertThatThrownBy(()->coordinator.retry(dataset,original,new RunCoordinator.Retry(UUID.randomUUID(),false,false))).hasMessageContaining("charges");
        assertThatThrownBy(()->coordinator.retry(dataset,original,new RunCoordinator.Retry(UUID.randomUUID(),true,false))).hasMessageContaining("uncertain");
        assertThat(fake.calls).isEqualTo(1);
        UUID retry=coordinator.retry(dataset,original,new RunCoordinator.Retry(UUID.randomUUID(),true,true));
        fake.action=()->{};coordinator.execute(dataset,retry);
        assertThat(fake.calls).isEqualTo(2);
        assertThat(store.results(original,0).get(0).get("status")).isEqualTo("UNCERTAIN");
        assertThat(store.results(retry,0).get(0).get("status")).isEqualTo("VALID");
        assertThat(db.queryForObject("select count(*) from evaluation_response",Integer.class)).isEqualTo(2);
    }
    @Test void retryRejectsActiveSuccessfulAndLegacyRunsWithoutSendingRequests(){
        UUID original=coordinator.start(dataset,plan(UUID.randomUUID(),1));
        assertThatThrownBy(()->coordinator.retryPlan(dataset,original)).hasMessageContaining("finish");
        assertThatThrownBy(()->store.delete(dataset,original)).hasMessageContaining("active run");
        coordinator.execute(dataset,original);
        assertThatThrownBy(()->coordinator.retry(dataset,original,new RunCoordinator.Retry(UUID.randomUUID(),true,false))).hasMessageContaining("no failed");
        UUID cancelled=coordinator.start(dataset,new RunCoordinator.Start(UUID.randomUUID(),List.of("gpt-4o"),1,"",256,true));store.cancel(cancelled);
        db.update("update evaluation_run_item set input_snapshot=jsonb_set(input_snapshot,'{contractVersion}','\"safety-judge-v1\"') where run_id=?",cancelled);
        assertThatThrownBy(()->coordinator.retry(dataset,cancelled,new RunCoordinator.Retry(UUID.randomUUID(),true,false))).hasMessageContaining("contract changed");
        assertThat(fake.calls).isEqualTo(1);
        assertThatThrownBy(()->store.delete(UUID.randomUUID(),cancelled)).isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(store.run(dataset,cancelled)).isNotEmpty();
    }
}
