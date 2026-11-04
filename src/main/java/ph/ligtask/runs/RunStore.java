package ph.ligtask.runs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RunStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public RunStore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public String json(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Cannot serialize run data",e);}}
    public Map<String,Object> decode(Map<String,Object> row){
        var value=new LinkedHashMap<>(row);
        for(String key:List.of("settings","input_snapshot","response")) if(value.get(key)!=null)
            try{value.put(key,json.readTree(value.get(key).toString()));}catch(Exception e){throw new IllegalStateException(e);}
        return value;
    }
    public Optional<Map<String,Object>> byToken(UUID token){return db.queryForList("select * from evaluation_run where client_token=?",token).stream().findFirst();}
    public Map<String,Object> run(UUID dataset,UUID id){return decode(db.queryForMap("select * from evaluation_run where id=? and dataset_id=?",id,dataset));}
    public List<Map<String,Object>> history(UUID dataset){return db.queryForList("select * from evaluation_run where dataset_id=? order by created_at desc limit 100",dataset).stream().map(this::decode).toList();}
    public Optional<Map<String,Object>> latestFinishedForModel(UUID dataset,String model){
        return db.queryForList("select r.* from evaluation_run r where r.dataset_id=? and r.status in ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','INTERRUPTED') and exists(select 1 from evaluation_run_item i where i.run_id=r.id and i.model_key=?) order by r.created_at desc,r.id desc limit 1",dataset,model).stream().map(this::decode).findFirst();
    }
    public List<Map<String,Object>> metricRows(UUID run,String model){
        return db.queryForList("select i.prompt_id,i.model_key,i.model_id,i.status,i.reused,r.classification,r.safety_rating,r.positive_probability from evaluation_run_item i left join evaluation_response r on r.id=i.response_id where i.run_id=? and i.model_key=? order by i.instance_id",run,model);
    }
    public List<Map<String,Object>> finished(UUID dataset,String model){
        return db.queryForList("select r.* from evaluation_run r where r.dataset_id=? and r.status in ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','INTERRUPTED') and (?::text is null or exists(select 1 from evaluation_run_item i where i.run_id=r.id and i.model_key=?)) order by r.created_at desc,r.id desc",dataset,model,model).stream().map(this::decode).toList();
    }
    public Optional<Map<String,Object>> promptResult(UUID run,UUID prompt,String model){
        return db.queryForList("select i.status,i.reused,r.classification,r.safety_rating,r.positive_probability,r.explanation,r.error_detail,r.response from evaluation_run_item i left join evaluation_response r on r.id=i.response_id where i.run_id=? and i.prompt_id=? and i.model_key=?",run,prompt,model).stream().map(this::decode).findFirst();
    }
    private void lifecycleLock(){db.execute("select pg_advisory_xact_lock(7264019052)");}
    public static boolean isActive(Object state){return List.of("QUEUED","RUNNING","CANCEL_REQUESTED").contains(state);}
    public Optional<UUID> active(){return db.queryForList("select id from evaluation_run where status in ('QUEUED','RUNNING','CANCEL_REQUESTED')").stream().map(x->(UUID)x.get("id")).findFirst();}
    @Transactional public UUID create(UUID dataset,UUID token,String planHash,Object settings,List<Map<String,Object>> items){
        lifecycleLock();
        UUID id=UUID.randomUUID();
        db.update("insert into evaluation_run(id,dataset_id,client_token,plan_hash,status,settings) values(?,?,?,?,'QUEUED',?::jsonb)",id,dataset,token,planHash,json(settings));
        for(var item:items)db.update("insert into evaluation_run_item(id,run_id,prompt_id,instance_id,model_key,model_id,fingerprint,input_snapshot,response_id,status,reused) values(?,?,?,?,?,?,?,?::jsonb,?,?,?)",UUID.randomUUID(),id,item.get("prompt_id"),item.get("instance_id"),item.get("model_key"),item.get("model_id"),item.get("fingerprint"),json(item.get("input_snapshot")),item.get("reuse_response_id"),item.containsKey("reuse_response_id")?"VALID":"PENDING",item.containsKey("reuse_response_id"));
        return id;
    }
    @Transactional public UUID createRetry(UUID dataset,UUID source,UUID token,String hash,Object settings,List<Map<String,Object>> items){
        lifecycleLock();
        var parent=db.queryForMap("select status from evaluation_run where dataset_id=? and id=? for update",dataset,source);
        if(isActive(parent.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for the source run to finish before retrying.");
        return create(dataset,token,hash,settings,items);
    }
    @Transactional public Map<String,Object> delete(UUID dataset,UUID run){
        lifecycleLock();
        var parent=db.queryForMap("select status from evaluation_run where dataset_id=? and id=? for update",dataset,run);
        if(isActive(parent.get("status"))||active().isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"Finish or stop the active run before deleting saved runs.");
        var ids=db.queryForList("select distinct response_id from evaluation_run_item where run_id=? and response_id is not null",UUID.class,run);
        int items=db.update("delete from evaluation_run_item where run_id=?",run),responses=0;
        for(UUID id:ids)responses+=db.update("delete from evaluation_response r where r.id=? and not exists(select 1 from evaluation_run_item i where i.response_id=r.id)",id);
        db.update("delete from evaluation_run where id=?",run);
        return Map.of("deletedRun",run,"deletedItems",items,"deletedResponses",responses,"sharedResponsesRetained",ids.size()-responses);
    }
    public boolean begin(UUID id){return db.update("update evaluation_run set status='RUNNING',started_at=now() where id=? and status='QUEUED'",id)==1;}
    public boolean cancelled(UUID id){return !db.queryForObject("select status from evaluation_run where id=?",String.class,id).equals("RUNNING");}
    public List<Map<String,Object>> items(UUID id){return db.queryForList("select * from evaluation_run_item where run_id=? order by instance_id,model_key",id).stream().map(this::decode).toList();}
    public List<Map<String,Object>> results(UUID id,int page){return db.queryForList("select i.id,i.instance_id,i.model_key,i.status,i.reused,i.input_snapshot,r.response,r.classification,r.safety_rating,r.positive_probability,r.explanation,r.error_detail,r.http_status,r.retry_after from evaluation_run_item i left join evaluation_response r on r.id=i.response_id where i.run_id=? order by i.instance_id,i.model_key limit 50 offset ?",id,page*50).stream().map(this::decode).toList();}
    public List<Map<String,Object>> export(UUID id){return db.queryForList("select i.*,r.response,r.classification,r.safety_rating,r.positive_probability,r.explanation,r.error_detail,r.http_status,r.retry_after from evaluation_run_item i left join evaluation_response r on r.id=i.response_id where i.run_id=? order by i.instance_id,i.model_key",id).stream().map(this::decode).toList();}
    public List<Map<String,Object>> counts(UUID id){return db.queryForList("select model_key,status,count(*) as count,count(*) filter(where reused) as reused from evaluation_run_item where run_id=? group by model_key,status",id);}
    public Optional<Map<String,Object>> previous(String fingerprint){return db.queryForList("select * from evaluation_response where fingerprint=?",fingerprint).stream().findFirst();}
    public Map<String,Object> claim(UUID dataset,Map<String,Object> item,Object settings){
        UUID candidate=UUID.randomUUID();
        var inserted=db.queryForList("insert into evaluation_response(id,fingerprint,dataset_id,prompt_id,model_key,model_id,input_snapshot,settings,status) values(?,?,?,?,?,?,?::jsonb,?::jsonb,'IN_FLIGHT') on conflict(fingerprint) do nothing returning id",candidate,item.get("fingerprint"),dataset,item.get("prompt_id"),item.get("model_key"),item.get("model_id"),json(item.get("input_snapshot")),json(settings));
        var response=new LinkedHashMap<>(db.queryForMap("select * from evaluation_response where fingerprint=?",item.get("fingerprint")));
        response.put("claimed",!inserted.isEmpty());return response;
    }
    public void attach(UUID item,UUID response,String status,boolean reused){db.update("update evaluation_run_item set response_id=?,status=?,reused=? where id=?",response,status,reused,item);}
    @Transactional public void finishResponse(UUID response,UUID item,String status,Object raw,String classification,Integer rating,Double probability,String explanation,String error,Integer http,String retry){
        int changed=db.update("update evaluation_response set status=?,response=?::jsonb,classification=?,safety_rating=?,positive_probability=?,explanation=?,error_detail=?,http_status=?,retry_after=?,finished_at=now() where id=? and status='IN_FLIGHT'",status,raw==null?null:json(raw),classification,rating,probability,explanation,error,http,retry,response);
        if(changed!=1)throw new IllegalStateException("Response reservation is no longer active; review before resending.");
        attach(item,response,status,false);
    }
    @Transactional public void finish(UUID id,boolean interrupted){
        String old=db.queryForObject("select status from evaluation_run where id=?",String.class,id);
        if(List.of("CANCELLED","INTERRUPTED","COMPLETED","COMPLETED_WITH_ERRORS").contains(old))return;
        String state=interrupted?"INTERRUPTED":old.equals("CANCEL_REQUESTED")?"CANCELLED":
            db.queryForObject("select count(*) from evaluation_run_item where run_id=? and status not in ('VALID')",Integer.class,id)>0?"COMPLETED_WITH_ERRORS":"COMPLETED";
        db.update("update evaluation_run_item set status='CANCELLED' where run_id=? and status='PENDING'",id);
        db.update("update evaluation_run set status=?,finished_at=now() where id=?",state,id);
    }
    @Transactional public void cancel(UUID id){
        db.update("update evaluation_run set status=case when status='QUEUED' then 'CANCELLED' else 'CANCEL_REQUESTED' end,finished_at=case when status='QUEUED' then now() else finished_at end where id=? and status in ('QUEUED','RUNNING')",id);
        if("CANCELLED".equals(db.queryForObject("select status from evaluation_run where id=?",String.class,id)))
            db.update("update evaluation_run_item set status='CANCELLED' where run_id=? and status='PENDING'",id);
    }
    @Transactional public void recover(){
        db.update("update evaluation_response set status='UNCERTAIN',error_detail='Application stopped before a final outcome was saved. Do not resend automatically.',finished_at=now() where status='IN_FLIGHT'");
        db.update("update evaluation_run_item set status='UNCERTAIN' where status='IN_FLIGHT'");
        db.update("update evaluation_run_item set status='CANCELLED' where status='PENDING'");
        db.update("update evaluation_run set status='INTERRUPTED',finished_at=now() where status in ('QUEUED','RUNNING','CANCEL_REQUESTED')");
    }
}
