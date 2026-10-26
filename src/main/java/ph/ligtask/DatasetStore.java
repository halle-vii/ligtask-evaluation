package ph.ligtask;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.security.MessageDigest;
@Service
public class DatasetStore {
 private final JdbcTemplate db;
 public DatasetStore(JdbcTemplate db){this.db=db;}
 public String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 public Optional<Map<String,Object>> existing(DatasetDocument d,byte[] bytes){
  return db.queryForList("select id,archived_at from dataset_version where (name=? and version=?) or source_sha256=? limit 1",d.name(),d.version(),hash(bytes)).stream().findFirst();
 }
 public boolean exists(DatasetDocument d,byte[] bytes){return existing(d,bytes).isPresent();}
 @Transactional public UUID save(DatasetDocument d,byte[] bytes,String filename){
  if(!d.valid())throw new IllegalArgumentException("Invalid document");
  UUID id=UUID.randomUUID();
  db.update("insert into dataset_version(id,name,version,schema_version,synthetic,source_filename,source_sha256,raw_document) values(?,?,?,?,?,?,?,?::jsonb)",id,d.name(),d.version(),d.schemaVersion(),d.synthetic(),filename,hash(bytes),d.raw().toString());
  for(JsonNode r:d.records()) db.update("insert into prompt_instance(id,dataset_id,instance_id,upstream_entry_id,base_query_id,context_id,context_condition,task_type,language_variant,prepared_prompt_text,context_text,policy_text,raw_record) values(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),id,r.path("instance_id").asText(),r.path("upstream_entry_id").asText(),r.path("base_query_id").asText(),r.path("context_id").asText(),r.path("context_condition").asText(),r.path("task_type").asText(),r.path("language_variant").asText(),r.path("prepared_prompt_text").asText(),nullable(r,"context_text"),nullable(r,"policy_text"),r.toString());
  return id;
 }
 private String nullable(JsonNode r,String key){return r.hasNonNull(key)?r.get(key).asText():null;}
 public List<Map<String,Object>> all(){return db.queryForList("select d.*, (select count(*) from prompt_instance p where p.dataset_id=d.id) as record_count from dataset_version d where archived_at is null order by imported_at desc");}
 public List<Map<String,Object>> archived(){return db.queryForList("select d.*, (select count(*) from prompt_instance p where p.dataset_id=d.id) as record_count from dataset_version d where archived_at is not null order by archived_at desc");}
 public boolean archive(UUID id){return db.update("update dataset_version set archived_at=coalesce(archived_at,current_timestamp) where id=?",id)>0;}
 public boolean restore(UUID id){return db.update("update dataset_version set archived_at=null where id=?",id)>0;}
 public Map<String,Object> dataset(UUID id){return db.queryForMap("select * from dataset_version where id=?",id);}
 public List<Map<String,Object>> records(UUID id){return db.queryForList("select * from prompt_instance where dataset_id=? order by instance_id",id);}
}
