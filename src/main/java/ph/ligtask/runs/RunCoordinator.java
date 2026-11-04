package ph.ligtask.runs;

import com.fasterxml.jackson.databind.*;
import ph.ligtask.*;
import ph.ligtask.llm.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClientResponseException;
import jakarta.annotation.*;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class RunCoordinator {
    public record Start(UUID clientToken,List<String> models,int promptLimit,String instanceId,int maxTokens,boolean acknowledgeCosts) {}
    public record Retry(UUID clientToken,boolean acknowledgeCosts,boolean acknowledgeUncertain) {}
    private final DatasetStore datasets;private final RunStore runs;private final LigtaskPromptBuilderService builder;
    private final LigtaskEvaluationService evaluation;private final LlmProviderService providers;private final ObjectMapper json;
    private final DataSource dataSource;private final boolean enabled;private final long interval;
    private Connection lock;private ExecutorService worker;
    public RunCoordinator(DatasetStore datasets,RunStore runs,LigtaskPromptBuilderService builder,LigtaskEvaluationService evaluation,
        LlmProviderService providers,ObjectMapper json,DataSource dataSource,
        @Value("${ligtask.runs.worker-enabled:true}") boolean enabled,@Value("${ligtask.runs.interval-ms:1500}") long interval){
        this.datasets=datasets;this.runs=runs;this.builder=builder;this.evaluation=evaluation;this.providers=providers;this.json=json;this.dataSource=dataSource;this.enabled=enabled;this.interval=Math.max(1000,interval);
    }
    @PostConstruct public void initialize() throws Exception {
        if(!enabled)return;
        // Session-level lock prevents a second process recovering a live worker's attempts.
        lock=dataSource.getConnection();
        try(var statement=lock.createStatement();var result=statement.executeQuery("select pg_try_advisory_lock(7264019051)")){
            result.next();if(!result.getBoolean(1)){lock.close();lock=null;throw new IllegalStateException("Another evaluation worker is already running.");}
        }
        runs.recover();
        worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"ligtask-run-worker");t.setDaemon(true);return t;});
    }
    @PreDestroy public void close() throws Exception {
        if(worker!=null){worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}
        if(lock!=null){try(var s=lock.createStatement()){s.execute("select pg_advisory_unlock(7264019051)");}finally{lock.close();}}
    }
    public Map<String,Boolean> availability(){var map=new LinkedHashMap<String,Boolean>();for(var model:providers.getAvailableModels())map.put(model.key(),providers.configured(model.key()));return map;}
    public Map<String,Object> retryPlan(UUID dataset,UUID source){
        var run=runs.run(dataset,source);
        if(RunStore.isActive(run.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for the run to finish before retrying.");
        var items=runs.items(source);
        long preserved=items.stream().filter(i->"VALID".equals(i.get("status"))).count();
        long uncertain=items.stream().filter(i->"UNCERTAIN".equals(i.get("status"))||"IN_FLIGHT".equals(i.get("status"))).count();
        return Map.of("sourceRun",source,"total",items.size(),"preserved",preserved,"requests",items.size()-preserved,"uncertain",uncertain);
    }
    public UUID retry(UUID dataset,UUID source,Retry request){
        if(request==null||request.clientToken()==null||!request.acknowledgeCosts())throw bad("Confirm that retrying may incur provider charges.");
        String planHash=hash(dataset+"|retry|"+source+"|"+LigtaskOutputContract.VERSION);
        var prior=runs.byToken(request.clientToken());
        if(prior.isPresent()){
            if(!dataset.equals(prior.get().get("dataset_id"))||!planHash.equals(prior.get().get("plan_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Retry token belongs to another plan.");
            return (UUID)prior.get().get("id");
        }
        var plan=retryPlan(dataset,source);
        if(((Number)plan.get("requests")).longValue()==0)throw bad("This run has no failed or unfinished requests to retry.");
        if(((Number)plan.get("uncertain")).longValue()>0&&!request.acknowledgeUncertain())throw bad("Acknowledge that uncertain requests may already have been processed and charged.");
        if(datasets.dataset(dataset).get("archived_at")!=null)throw bad("Restore this dataset before retrying.");
        var original=runs.run(dataset,source);var settings=(JsonNode)original.get("settings");
        int max=settings.path("maxTokens").asInt();
        if(max<64||max>4096||settings.path("temperature").asDouble()!=0)throw bad("Unsupported original run settings.");
        var items=new ArrayList<Map<String,Object>>();
        for(var item:runs.items(source)){
            var copy=new LinkedHashMap<String,Object>(item);
            if("VALID".equals(item.get("status"))){
                if(item.get("response_id")==null)throw bad("A saved valid response is missing; retry cannot preserve it.");
                copy.put("reuse_response_id",item.get("response_id"));
            }else{
                String key=item.get("model_key").toString();
                if(!providers.configured(key))throw bad("API key is not configured for "+key+".");
                var configured=providers.getAvailableModels().stream().filter(m->m.key().equals(key)).findFirst().orElseThrow(()->bad("Unknown model."));
                if(!configured.modelId().equals(item.get("model_id")))throw bad("The configured model changed. Start a new run instead of retrying this snapshot.");
                try{
                    var input=json.treeToValue((JsonNode)item.get("input_snapshot"),PreparedEvaluationInput.class);
                    if(!LigtaskOutputContract.VERSION.equals(input.contractVersion())||!hash(input.prompt()).equals(input.promptSha256()))throw bad("The original prompt contract changed or its checksum is invalid. Start a new run instead.");
                }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw bad("The original input snapshot cannot be read.");}
                copy.put("fingerprint",hash(item.get("fingerprint")+"|retry|"+request.clientToken()));
            }
            items.add(copy);
        }
        var retrySettings=((com.fasterxml.jackson.databind.node.ObjectNode)settings).deepCopy();
        retrySettings.put("retryOf",source.toString());retrySettings.put("retryRequests",((Number)plan.get("requests")).longValue());
        retrySettings.put("acknowledgeUncertain",request.acknowledgeUncertain());retrySettings.put("contractVersion",LigtaskOutputContract.VERSION);
        try{
            UUID id=runs.createRetry(dataset,source,request.clientToken(),planHash,retrySettings,items);
            if(enabled&&worker!=null)worker.submit(()->execute(dataset,id));
            return id;
        }catch(DataIntegrityViolationException e){
            var existing=runs.byToken(request.clientToken());
            if(existing.isPresent()&&dataset.equals(existing.get().get("dataset_id"))&&planHash.equals(existing.get().get("plan_hash")))return (UUID)existing.get().get("id");
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Another run is active. Wait for it to finish before retrying.");
        }
    }
    public UUID start(UUID dataset,Start request){
        if(request==null||request.clientToken()==null||!request.acknowledgeCosts())throw bad("Confirm that provider charges may apply before starting.");
        if(request.models()==null||request.models().isEmpty()||request.models().size()>7||new HashSet<>(request.models()).size()!=request.models().size())throw bad("Select distinct models.");
        if(request.promptLimit()<1||request.promptLimit()>9999||request.maxTokens()<64||request.maxTokens()>4096)throw bad("Invalid prompt or output limit.");
        var modelKeys=request.models().stream().sorted().toList();
        var settings=new LinkedHashMap<String,Object>();settings.put("models",modelKeys);settings.put("promptLimit",request.promptLimit());
        settings.put("instanceId",Objects.toString(request.instanceId(),""));settings.put("maxTokens",request.maxTokens());settings.put("temperature",0);
        settings.put("contractVersion",LigtaskOutputContract.VERSION);settings.put("automaticRetries",false);
        String planHash=hash(dataset+"|"+runs.json(settings));
        var prior=runs.byToken(request.clientToken());
        if(prior.isPresent()){
            if(!dataset.equals(prior.get().get("dataset_id"))||!planHash.equals(prior.get().get("plan_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Start token belongs to another plan.");
            return (UUID)prior.get().get("id");
        }
        try{if(datasets.dataset(dataset).get("archived_at")!=null)throw bad("Restore this dataset before starting a run.");}catch(EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        for(String key:modelKeys)if(!providers.configured(key))throw bad("API key is not configured for "+key+".");
        var rows=datasets.records(dataset);
        if(request.instanceId()!=null&&!request.instanceId().isBlank())rows=rows.stream().filter(r->request.instanceId().equals(r.get("instance_id"))).toList();
        rows=rows.stream().limit(request.promptLimit()).toList();
        if(rows.isEmpty())throw bad("No matching prepared prompts.");
        var items=new ArrayList<Map<String,Object>>();
        // Validate every input before saving or scheduling any paid request.
        for(var row:rows){
            PreparedEvaluationInput input;
            try{input=builder.prepareRecord(dataset,row);}catch(RuntimeException e){throw bad("Input preparation failed for "+row.get("instance_id")+": "+e.getMessage());}
            for(String key:modelKeys){
                var model=providers.getAvailableModels().stream().filter(m->m.key().equals(key)).findFirst().orElseThrow(()->bad("Unknown model."));
                String fingerprint=hash(dataset+"|"+row.get("id")+"|"+model.modelId()+"|"+input.promptSha256()+"|"+request.maxTokens()+"|0|"+LigtaskOutputContract.responseFormat(LigtaskOutputContract.schema(input.taskType())));
                items.add(Map.of("prompt_id",row.get("id"),"instance_id",input.instanceId(),"model_key",key,"model_id",model.modelId(),"fingerprint",fingerprint,"input_snapshot",input));
            }
        }
        try{
            UUID id=runs.create(dataset,request.clientToken(),planHash,settings,items);
            if(enabled&&worker!=null)worker.submit(()->execute(dataset,id));
            return id;
        }catch(DataIntegrityViolationException e){
            var existing=runs.byToken(request.clientToken());
            if(existing.isPresent()&&planHash.equals(existing.get().get("plan_hash")))return (UUID)existing.get().get("id");
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Another run is active. View its progress before starting another.");
        }
    }
    public void execute(UUID dataset,UUID id){
        if(!runs.begin(id))return;
        boolean interrupted=false;
        try{
            var run=runs.run(dataset,id);var settings=(JsonNode)run.get("settings");int max=settings.path("maxTokens").asInt();
            boolean sent=false;
            for(var item:runs.items(id)){
                if(runs.cancelled(id)||Thread.currentThread().isInterrupted())break;
                if(!"PENDING".equals(item.get("status")))continue;
                var previous=runs.previous(item.get("fingerprint").toString());
                if(previous.isPresent()){
                    var prior=previous.get();String state=prior.get("status").toString();
                    runs.attach((UUID)item.get("id"),(UUID)prior.get("id"),state.equals("IN_FLIGHT")?"UNCERTAIN":state,true);continue;
                }
                if(sent){pause();if(runs.cancelled(id)||Thread.currentThread().isInterrupted())break;}
                var claim=runs.claim(dataset,item,settings);UUID response=(UUID)claim.get("id");UUID itemId=(UUID)item.get("id");
                if(!Boolean.TRUE.equals(claim.get("claimed"))){
                    String state=claim.get("status").toString();
                    runs.attach(itemId,response,state.equals("IN_FLIGHT")?"UNCERTAIN":state,true);continue;
                }
                runs.attach(itemId,response,"IN_FLIGHT",false);
                try{
                    var input=json.treeToValue((JsonNode)item.get("input_snapshot"),PreparedEvaluationInput.class);
                    sent=true;
                    var result=evaluation.evaluatePrepared(input,item.get("model_key").toString(),0,max);
                    var value=result.judgment();
                    runs.finishResponse(response,itemId,result.valid()?"VALID":"INVALID_RESPONSE",result.rawResponse(),value==null?null:value.classification(),value==null?null:value.safetyRating(),value==null?null:value.positiveProbability(),value==null?null:value.explanation(),result.validationError(),null,null);
                }catch(RestClientResponseException e){
                    String body=e.getResponseBodyAsString();
                    // Store only selected error fields, not arbitrary headers or credentials.
                    String detail="HTTP "+e.getStatusCode().value();
                    try{var error=json.readTree(body).path("error");String code=error.path("code").asText();String message=error.path("message").asText();detail+=" "+code+": "+message;}catch(Exception ignored){}
                    runs.finishResponse(response,itemId,"HTTP_ERROR",Map.of("providerErrorBody",providers.redactError(body)),null,null,null,null,providers.redactError(detail).substring(0,Math.min(providers.redactError(detail).length(),2000)),e.getStatusCode().value(),e.getResponseHeaders()==null?null:e.getResponseHeaders().getFirst("Retry-After"));
                    // Stop on rate/access/schema errors; never hammer the provider or silently retry.
                    if(e.getStatusCode().is4xxClientError()){interrupted=true;break;}
                }catch(Exception e){
                    // A timeout or crash may happen after transmission: never resend automatically.
                    runs.finishResponse(response,itemId,"UNCERTAIN",null,null,null,null,null,"Request outcome requires review ("+e.getClass().getSimpleName()+"). No automatic retry.",null,null);
                    interrupted=true;break;
                }
            }
        }catch(Exception e){interrupted=true;org.slf4j.LoggerFactory.getLogger(RunCoordinator.class).warn("Run {} interrupted by {}",id,e.getClass().getSimpleName());}
        finally{runs.finish(id,interrupted||Thread.currentThread().isInterrupted());}
    }
    protected void pause() throws InterruptedException {Thread.sleep(interval);}
    private ResponseStatusException bad(String text){return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
