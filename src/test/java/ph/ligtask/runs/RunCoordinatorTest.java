package ph.ligtask.runs;

import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.web.client.*;
import org.springframework.http.*;
import javax.sql.DataSource;
import java.util.*;
import ph.ligtask.*;
import ph.ligtask.llm.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class RunCoordinatorTest {
    final ObjectMapper json=new ObjectMapper();final UUID dataset=UUID.randomUUID(),run=UUID.randomUUID(),prompt=UUID.randomUUID(),item=UUID.randomUUID(),response=UUID.randomUUID();
    DatasetStore datasets;RunStore store;LigtaskPromptBuilderService builder;LigtaskEvaluationService evaluation;LlmProviderService providers;RunCoordinator coordinator;
    PreparedEvaluationInput input;
    @BeforeEach void setup() throws Exception {
        datasets=mock(DatasetStore.class);store=mock(RunStore.class);builder=mock(LigtaskPromptBuilderService.class);evaluation=mock(LigtaskEvaluationService.class);providers=mock(LlmProviderService.class);
        input=new PreparedEvaluationInput(dataset,"TEST_NLG_EN","NLG","EN",LigtaskOutputContract.VERSION,"Synthetic prompt","synthetic-hash");
        when(datasets.dataset(dataset)).thenReturn(new HashMap<>());
        when(datasets.records(dataset)).thenReturn(List.of(Map.of("id",prompt,"instance_id",input.instanceId())));
        when(builder.prepareRecord(eq(dataset),anyMap())).thenReturn(input);
        when(providers.getAvailableModels()).thenReturn(ModelConfiguration.MODELS);
        when(providers.configured("gpt-4o")).thenReturn(true);
        when(providers.redactError(anyString())).thenAnswer(i->i.getArgument(0));
        when(store.json(any())).thenAnswer(i->json.writeValueAsString(i.getArgument(0)));
        when(store.create(eq(dataset),any(),anyString(),any(),anyList())).thenReturn(run);
        coordinator=new RunCoordinator(datasets,store,builder,evaluation,providers,json,mock(DataSource.class),false,1000){@Override protected void pause(){}};
    }
    RunCoordinator.Start plan(UUID token,boolean ack){return new RunCoordinator.Start(token,List.of("gpt-4o"),1,"",512,ack);}
    void execution(){
        when(store.begin(run)).thenReturn(true);when(store.run(dataset,run)).thenReturn(Map.of("settings",json.createObjectNode().put("maxTokens",512)));
        when(store.items(run)).thenReturn(List.of(Map.of("id",item,"prompt_id",prompt,"instance_id",input.instanceId(),"model_key","gpt-4o","fingerprint","fingerprint","input_snapshot",json.valueToTree(input),"status","PENDING")));
        when(store.claim(eq(dataset),anyMap(),any())).thenReturn(Map.of("id",response,"claimed",true,"status","IN_FLIGHT"));
    }
    @Test void acknowledgedPlanSnapshotsInputsBeforeSchedulingAnyProvider(){
        assertThat(coordinator.start(dataset,plan(UUID.randomUUID(),true))).isEqualTo(run);
        verify(builder).prepareRecord(eq(dataset),anyMap());verify(store).create(eq(dataset),any(),anyString(),any(),argThat(items->items.size()==1&&items.get(0).get("input_snapshot").equals(input)));
        verifyNoInteractions(evaluation);
    }
    @Test void costAcknowledgementAndCredentialsAreRequired(){
        assertThatThrownBy(()->coordinator.start(dataset,plan(UUID.randomUUID(),false))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        when(providers.configured("gpt-4o")).thenReturn(false);
        assertThatThrownBy(()->coordinator.start(dataset,plan(UUID.randomUUID(),true))).hasMessageContaining("API key");
        verify(store,never()).create(any(),any(),anyString(),any(),anyList());verifyNoInteractions(evaluation);
    }
    @Test void duplicateStartTokenReturnsSavedRunWithoutRescheduling() throws Exception {
        UUID token=UUID.randomUUID();final String[] hash={null};
        when(store.create(eq(dataset),eq(token),anyString(),any(),anyList())).thenAnswer(i->{hash[0]=i.getArgument(2);return run;});
        coordinator.start(dataset,plan(token,true));
        when(store.byToken(token)).thenReturn(Optional.of(Map.of("id",run,"dataset_id",dataset,"plan_hash",hash[0])));
        assertThat(coordinator.start(dataset,plan(token,true))).isEqualTo(run);
        verify(store,times(1)).create(eq(dataset),eq(token),anyString(),any(),anyList());
    }
    @Test void reusedTokenCannotChangePlan(){
        UUID token=UUID.randomUUID();when(store.byToken(token)).thenReturn(Optional.of(Map.of("id",run,"dataset_id",dataset,"plan_hash","another-plan")));
        assertThatThrownBy(()->coordinator.start(dataset,plan(token,true))).hasMessageContaining("another plan");
    }
    @Test void invalidInputAbortsWholePlanBeforeAnyCalls(){
        when(builder.prepareRecord(eq(dataset),anyMap())).thenThrow(new IllegalArgumentException("Invalid schema"));
        assertThatThrownBy(()->coordinator.start(dataset,plan(UUID.randomUUID(),true))).hasMessageContaining("Input preparation failed");
        verify(store,never()).create(any(),any(),anyString(),any(),anyList());verifyNoInteractions(evaluation);
    }
    @Test void validResponseIsPersistedAndLinkedToExactSnapshot(){
        execution();var raw=new LlmResponse("model","original JSON",null,"stop","request-id",10,20);
        when(evaluation.evaluatePrepared(input,"gpt-4o",0,512)).thenReturn(new LigtaskEvaluationService.Outcome("gpt-4o",input,raw,new LigtaskOutputContract.Judgment("Answer","Synthetic reason",8,0.2),null));
        coordinator.execute(dataset,run);
        verify(evaluation,times(1)).evaluatePrepared(input,"gpt-4o",0,512);
        verify(store).finishResponse(response,item,"VALID",raw,"Answer",8,0.2,"Synthetic reason",null,null,null);
        verify(store).finish(run,false);
    }
    @Test void invalidCompletedResponseIsSavedWithoutRetry(){
        execution();var raw=new LlmResponse("model","```json {} ```",null,"stop","request-id",10,20);
        when(evaluation.evaluatePrepared(input,"gpt-4o",0,512)).thenReturn(new LigtaskEvaluationService.Outcome("gpt-4o",input,raw,null,"Invalid JSON"));
        coordinator.execute(dataset,run);
        verify(evaluation,times(1)).evaluatePrepared(any(),anyString(),anyDouble(),anyInt());
        verify(store).finishResponse(response,item,"INVALID_RESPONSE",raw,null,null,null,null,"Invalid JSON",null,null);
    }
    @Test void everyRecordedAttemptIsReusedIncludingUncertainAndInvalid(){
        for(String status:List.of("VALID","INVALID_RESPONSE","HTTP_ERROR","UNCERTAIN","IN_FLIGHT")){
            reset(store);execution();when(store.previous("fingerprint")).thenReturn(Optional.of(Map.of("id",response,"status",status)));
            coordinator.execute(dataset,run);
            verify(store).attach(item,response,status.equals("IN_FLIGHT")?"UNCERTAIN":status,true);
            verify(store,never()).claim(any(),anyMap(),any());
        }
        verifyNoInteractions(evaluation);
    }
    @Test void cancelledRunDoesNotSendNextRequest(){
        execution();when(store.cancelled(run)).thenReturn(true);coordinator.execute(dataset,run);verifyNoInteractions(evaluation);verify(store).finish(run,false);
    }
    @Test void rateLimitIsSavedWithRetryGuidanceButNotRetried(){
        execution();var headers=new HttpHeaders();headers.set("Retry-After","30");
        when(evaluation.evaluatePrepared(input,"gpt-4o",0,512)).thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,"Limited",headers,"{\"error\":{\"message\":\"Try later\",\"code\":429}}".getBytes(),null));
        coordinator.execute(dataset,run);
        verify(store).finishResponse(eq(response),eq(item),eq("HTTP_ERROR"),argThat(raw->raw.toString().contains("Try later")),isNull(),isNull(),isNull(),isNull(),contains("Try later"),eq(429),eq("30"));
        verify(evaluation,times(1)).evaluatePrepared(any(),anyString(),anyDouble(),anyInt());
    }
    @Test void timeoutIsUncertainRatherThanARefusal(){
        execution();when(evaluation.evaluatePrepared(input,"gpt-4o",0,512)).thenThrow(new ResourceAccessException("Synthetic timeout"));
        coordinator.execute(dataset,run);
        verify(store).finishResponse(eq(response),eq(item),eq("UNCERTAIN"),isNull(),isNull(),isNull(),isNull(),isNull(),contains("No automatic retry"),isNull(),isNull());
        verify(evaluation,times(1)).evaluatePrepared(any(),anyString(),anyDouble(),anyInt());
    }
}
