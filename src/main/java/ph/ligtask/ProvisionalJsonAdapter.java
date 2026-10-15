package ph.ligtask;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class ProvisionalJsonAdapter {
 private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
  .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 public DatasetDocument parse(byte[] bytes) {
  List<String> errors=new ArrayList<>(), warnings=new ArrayList<>();
  JsonNode root;
  try {root=mapper.readTree(bytes);} catch(Exception e){return bad("Invalid JSON. Check syntax, duplicate keys, and trailing content.");}
  if(root==null || !root.isObject()) return bad("Root must be an object under provisional-v1.");
  String name=required(root,"name","Dataset",errors), version=required(root,"version","Dataset",errors);
  String schema=required(root,"schema_version","Dataset",errors);
  if(!schema.equals("provisional-v1")) errors.add("Dataset: schema_version must be provisional-v1; real export adapter is pending.");
  if(!root.path("synthetic").isBoolean()) errors.add("Dataset: synthetic must be a boolean.");
  List<JsonNode> records=new ArrayList<>();
  JsonNode array=root.path("records");
  if(!array.isArray() || array.isEmpty()) errors.add("Dataset: records must be a nonempty array.");
  else if(array.size()>10000) errors.add("Dataset: limit is 10,000 records per import.");
  else {
   Set<String> ids=new HashSet<>(), variants=new HashSet<>(); Map<String,String> contexts=new HashMap<>(), entries=new HashMap<>(), contextOwners=new HashMap<>();
   int row=0, missing=0;
   for(JsonNode r:array) {
    row++; String at="Record "+row;
    if(!r.isObject()){errors.add(at+": must be an object.");continue;}
    for(String f:List.of("instance_id","upstream_entry_id","base_query_id","context_id","context_condition","task_type","language_variant","prepared_prompt_text")) required(r,f,at,errors);
    for(var rule:Map.of("context_condition",Set.of("SAFE","UNSAFE","UNKNOWN"),"task_type",Set.of("NLU","NLR","NLG"),"language_variant",Set.of("EN","FIL")).entrySet())
     if(!rule.getValue().contains(r.path(rule.getKey()).asText())) errors.add(at+": "+rule.getKey()+" must be one of "+new TreeSet<>(rule.getValue()));
    for(String f:List.of("context_text","policy_text")) if(r.hasNonNull(f)&&!r.get(f).isTextual()) errors.add(at+": "+f+" must be text or null.");
    if(!ids.add(r.path("instance_id").asText())) errors.add(at+": duplicate instance_id.");
    String variant=key(r,"base_query_id","context_condition","task_type","language_variant");
    if(!variants.add(variant)) errors.add(at+": duplicate base/context/task/language variant.");
    String context=key(r,"base_query_id","context_condition");
    String previous=contexts.putIfAbsent(context,r.path("context_id").asText());
    if(previous!=null&&!previous.equals(r.path("context_id").asText())) errors.add(at+": inconsistent context_id for base query and condition.");
    String owner=key(r,"base_query_id","context_condition");
    previous=contextOwners.putIfAbsent(r.path("context_id").asText(),owner);
    if(previous!=null&&!previous.equals(owner)) errors.add(at+": context_id has conflicting base/condition relationship.");
    String entry=r.path("upstream_entry_id").asText(), relation=key(r,"base_query_id","context_id","task_type");
    previous=entries.putIfAbsent(entry,relation);
    if(previous!=null&&!previous.equals(relation)) errors.add(at+": upstream_entry_id has conflicting base/context/task relationship.");
    if(r.path("context_text").asText("").isBlank()) missing++;
    records.add(r);
   }
   if(missing>0) warnings.add(missing+" records have no context text.");
  }
  return new DatasetDocument(name,version,schema,root.path("synthetic").asBoolean(),root,records,errors,warnings);
 }
 private String required(JsonNode r,String f,String at,List<String> errors){JsonNode n=r.path(f);if(!n.isTextual()||n.asText().isBlank()){errors.add(at+": "+f+" is required text.");return "";}return n.asText();}
 private String key(JsonNode n,String... fields){return Arrays.stream(fields).map(f->n.path(f).toString()).reduce("",(a,b)->a+"|"+b);}
 private DatasetDocument bad(String error){return new DatasetDocument("","","",false,null,List.of(),List.of(error),List.of());}
}
