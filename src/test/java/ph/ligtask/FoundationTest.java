package ph.ligtask;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://127.0.0.1:55432/ligtask_test}"})
@AutoConfigureMockMvc
class FoundationTest {
 @Autowired ProvisionalJsonAdapter adapter; @Autowired DatasetStore store; @Autowired JdbcTemplate db; @Autowired MockMvc mvc;
 byte[] sample() throws Exception{return Files.readAllBytes(Path.of("samples/synthetic-provisional-v1.json"));}
 @BeforeEach void clean(){
  String database=db.queryForObject("select current_database()",String.class);
  if(!database.endsWith("_test"))throw new IllegalStateException("Tests require a dedicated database ending _test");
  db.execute("truncate prompt_instance,dataset_version");
 }
 @Test void fullAndPartialCoverage() throws Exception {
  var d=adapter.parse(sample());assertThat(d.errors()).isEmpty();var c=Coverage.of(d.records());
  assertThat(c.records()).isEqualTo(24);assertThat(c.bases()).isEqualTo(2);assertThat(c.cells().stream().filter(x->!x.context().equals("UNKNOWN"))).allMatch(x->x.count()==2);assertThat(c.missingVariants()).isEmpty();
  var p=adapter.parse(Files.readAllBytes(Path.of("samples/synthetic-partial.json")));
  assertThat(p.valid()).isTrue();assertThat(Coverage.of(p.records()).missingContext()).isEqualTo(1);assertThat(Coverage.of(p.records()).missingVariants()).hasSize(9);
 }
 @Test void validatesFieldsValuesDuplicatesAndMalformedJson() throws Exception {
  var bad=adapter.parse(Files.readAllBytes(Path.of("samples/invalid-provisional.json")));
  assertThat(bad.errors().toString()).contains("duplicate instance_id","task_type","language_variant","context_condition","prepared_prompt_text");
  var root=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(sample());
  ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("records").get(0)).remove("base_query_id");
  assertThat(adapter.parse(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).errors().toString()).contains("base_query_id is required");
  assertThat(adapter.parse("{\"name\":1,\"name\":2}".getBytes()).valid()).isFalse();
  assertThat(adapter.parse("{} {}".getBytes()).valid()).isFalse();
 }
 @Test void previewThenConfirmAndRepeatProtection() throws Exception {
  var session=new MockHttpSession();
  mvc.perform(multipart("/preview").file(new MockMultipartFile("file","sample.json","application/json",sample())).session(session).with(csrf())).andExpect(status().isOk()).andExpect(view().name("preview")).andExpect(content().string(org.hamcrest.Matchers.containsString("Confirm import")));
  assertThat(store.all()).isEmpty();
  var pending=(WebController.Pending)session.getAttribute("pending");
  var result=mvc.perform(post("/import").session(session).with(csrf()).param("token",pending.token())).andExpect(status().is3xxRedirection()).andReturn();
  assertThat(store.all()).hasSize(1);UUID id=(UUID)store.all().get(0).get("id");assertThat(store.records(id)).hasSize(24);
  mvc.perform(get(result.getResponse().getRedirectedUrl())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Dataset composition")));
  mvc.perform(get("/evaluation/"+id+"/prompts").param("q","paanyaya")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("paanyaya")));
  mvc.perform(post("/import").session(session).with(csrf()).param("token",pending.token())).andExpect(redirectedUrl("/"));
  mvc.perform(multipart("/preview").file(new MockMultipartFile("file",sample())).with(csrf())).andExpect(content().string(org.hamcrest.Matchers.containsString("already imported")));
  assertThat(store.all()).hasSize(1);
 }
 @Test void invalidUploadsAndCsrfCannotImport() throws Exception {
  var session=new MockHttpSession();
  mvc.perform(multipart("/preview").file(new MockMultipartFile("file",Files.readAllBytes(Path.of("samples/invalid-provisional.json")))).session(session).with(csrf())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Import blocked")));
  assertThat(session.getAttribute("pending")).isNull();assertThat(store.all()).isEmpty();
  mvc.perform(post("/import").param("token","fake")).andExpect(status().isForbidden());
  mvc.perform(get("/datasets/"+UUID.randomUUID())).andExpect(status().isNotFound());
 }
 @Test void databaseFailureRollsBackWholeDataset() throws Exception {
  var d=adapter.parse(sample());var rows=new ArrayList<>(d.records());rows.add(rows.get(0));
  var invalid=new DatasetDocument(d.name(),d.version(),d.schemaVersion(),true,d.raw(),rows,List.of(),List.of());
  assertThatThrownBy(()->store.save(invalid,sample(),"test.json")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThat(store.all()).isEmpty();assertThat(db.queryForObject("select count(*) from prompt_instance",Integer.class)).isZero();
 }
 @Test void rejectsConflictingContextRelationships() throws Exception {
  var root=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(sample());
  var row=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("records").get(6);
  row.put("context_id",root.path("records").get(0).path("context_id").asText());
  assertThat(adapter.parse(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).errors().toString()).contains("conflicting base/condition");
 }
 @Test void preservesUnknownContextAndNeverDerivesGroundTruth() throws Exception {
  var root=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(sample());
  var r=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("records").get(0);
  r.put("context_condition","UNKNOWN");r.putNull("context_text");r.put("custom_upstream_field","preserve me");root.putArray("records").add(r);
  var bytes=root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);var d=adapter.parse(bytes);assertThat(d.valid()).isTrue();
  UUID id=store.save(d,bytes,"unknown.json");var saved=store.records(id).stream().filter(x->x.get("instance_id").equals(r.path("instance_id").asText())).findFirst().orElseThrow();
  assertThat(saved.get("context_condition")).isEqualTo("UNKNOWN");assertThat(saved.get("context_text")).isNull();assertThat(saved.get("raw_record").toString()).contains("preserve me","selected_version");assertThat(saved).doesNotContainKey("ground_truth");
 }

 @Test void evaluationOverviewAndFilteredResultsRenderWithoutInventedMetrics() throws Exception {
  UUID id=store.save(adapter.parse(sample()),sample(),"demo.json");
  mvc.perform(get("/evaluation")).andExpect(redirectedUrl("/evaluation/"+id));
  mvc.perform(get("/evaluation/"+id)).andExpect(status().isOk())
   .andExpect(content().string(org.hamcrest.Matchers.containsString("LIGTask Benchmark")))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("Claude-3.5-sonnet")))
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("98.0%"))));
  var result=mvc.perform(get("/evaluation/"+id+"/models/claude-3-5-sonnet")
   .param("language","FIL").param("context","SAFE").param("task","NLU"))
   .andExpect(status().isOk()).andExpect(view().name("evaluation-results"))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("Not evaluated"))).andReturn();
  var page=(EvaluationView.Page)result.getModelAndView().getModel().get("page");
  assertThat(page.count()).isEqualTo(2);assertThat(page.total()).isEqualTo(24);
  assertThat(page.rows()).allMatch(r->r.get("language_variant").equals("FIL")&&r.get("context_condition").equals("SAFE")&&r.get("task_type").equals("NLU"));
  String search="paanyaya";
  result=mvc.perform(get("/evaluation/"+id+"/prompts").param("q",search)).andExpect(status().isOk()).andReturn();
  assertThat(((EvaluationView.Page)result.getModelAndView().getModel().get("page")).count()).isEqualTo(6);
 }
 @Test void paginationAndPromptNavigationPreserveSelectionAndRejectWrongDataset() throws Exception {
  UUID id=store.save(adapter.parse(sample()),sample(),"demo.json");
  var result=mvc.perform(get("/evaluation/"+id+"/models/gpt-4o").param("page","1"))
   .andExpect(status().isOk()).andReturn();
  var page=(EvaluationView.Page)result.getModelAndView().getModel().get("page");
  assertThat(page.rows()).hasSize(12);assertThat(page.start()).isEqualTo(13);assertThat(page.end()).isEqualTo(24);
  UUID prompt=(UUID)page.rows().get(0).get("id");
  result=mvc.perform(get("/evaluation/"+id+"/prompts/"+prompt).param("model","gpt-4o").param("page","1"))
   .andExpect(status().isOk()).andExpect(view().name("evaluation-prompt")).andReturn();
  assertThat(result.getModelAndView().getModel().get("backUrl").toString()).contains("/models/gpt-4o","page=1");
  assertThat(result.getModelAndView().getModel().get("previousPrompt")).isNotNull();
  var partial=Files.readAllBytes(Path.of("samples/synthetic-partial.json"));
  UUID other=store.save(adapter.parse(partial),partial,"partial.json");
  mvc.perform(get("/evaluation/"+other+"/prompts/"+prompt)).andExpect(status().isNotFound());
  mvc.perform(get("/evaluation/"+id+"/prompts/"+prompt).param("language","BAD")).andExpect(status().isBadRequest());
 }
 @Test void exportsEveryFilteredRecordRatherThanOnlyCurrentPage() throws Exception {
  UUID id=store.save(adapter.parse(sample()),sample(),"demo.json");
  var result=mvc.perform(get("/evaluation/"+id+"/export.csv").param("model","gpt-4o"))
   .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=\"ligtask-prepared-prompts.csv\""))
   .andReturn();
  String csv=result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  assertThat(csv.lines().count()).isEqualTo(25);assertThat(csv).contains("paanyaya","NOT_EVALUATED","human_label,model_label");
  result=mvc.perform(get("/evaluation/"+id+"/export.csv").param("language","FIL").param("context","SAFE").param("task","NLU"))
   .andExpect(status().isOk()).andReturn();
  assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).lines().count()).isEqualTo(3);
 }
 @Test void emptyAndInvalidEvaluationStatesAreExplicit() throws Exception {
  mvc.perform(get("/evaluation")).andExpect(status().isOk()).andExpect(view().name("evaluation-empty"));
  UUID id=store.save(adapter.parse(sample()),sample(),"demo.json");
  mvc.perform(get("/evaluation/"+id+"/models/missing-model")).andExpect(status().isNotFound());
  mvc.perform(get("/evaluation/"+id+"/prompts").param("page","-1")).andExpect(status().isBadRequest());
  mvc.perform(get("/evaluation/"+id+"/prompts").param("task","BAD")).andExpect(status().isBadRequest());
  mvc.perform(get("/evaluation/"+id+"/prompts").param("q","no-matching-record-xyz"))
   .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("No matching prompts")));
 }
 @Test void promptSourceAndCsvAreEscapedAndMissingContextRemainsUnknown() throws Exception {
  var root=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(sample());
  var row=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("records").get(0);
  row.put("context_condition","UNKNOWN");row.putNull("context_text");
  row.put("prepared_prompt_text","=SUM(1,2) <script>alert('x')</script>");
  row.putObject("context_attributes").put("platform","<script>source</script>");
  root.putArray("records").add(row);
  byte[] data=root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  UUID id=store.save(adapter.parse(data),data,"escape.json");
  UUID prompt=(UUID)store.records(id).get(0).get("id");
  mvc.perform(get("/evaluation/"+id+"/prompts/"+prompt))
   .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Unknown / not supplied")))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>alert"))));
  var result=mvc.perform(get("/evaluation/"+id+"/export.csv")).andExpect(status().isOk()).andReturn();
  assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("'=SUM");
 }

 @Test void evaluationSidebarIsSharedAcrossEveryWorkspacePage() throws Exception {
  UUID id=store.save(adapter.parse(sample()),sample(),"demo.json");
  UUID prompt=(UUID)store.records(id).get(0).get("id");
  for(String path:List.of("/evaluation/"+id,"/evaluation/"+id+"/prompts","/evaluation/"+id+"/models/gpt-4o","/evaluation/"+id+"/prompts/"+prompt,"/evaluation/"+id+"/coverage")) {
   mvc.perform(get(path)).andExpect(status().isOk())
    .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"Evaluation navigation\"")))
    .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"sidebar-toggle\"")))
    .andExpect(content().string(org.hamcrest.Matchers.containsString("/sidebar-state.js")))
    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("class=\"back-button"))));
  }
  var result=mvc.perform(get("/evaluation/"+id+"/prompts/"+prompt).param("model","gpt-4o").param("page","1"))
   .andExpect(status().isOk()).andReturn();
  assertThat(result.getModelAndView().getModel().get("modelResultsUrl").toString()).contains("/models/gpt-4o", "page=1");
 }
 @Test void coverageUsesCanonicalRouteAndKeepsNavigationAndDatasetSelectionContext() throws Exception {
  UUID id=store.save(adapter.parse(sample()),sample(),"demo.json");
  var partial=Files.readAllBytes(Path.of("samples/synthetic-partial.json"));
  UUID other=store.save(adapter.parse(partial),partial,"partial.json");
  mvc.perform(get("/datasets/"+id)).andExpect(redirectedUrl("/evaluation/"+id+"/coverage"));
  var result=mvc.perform(get("/evaluation/"+id+"/coverage").param("task","NLU").param("language","FIL")
   .param("q","paanyaya").param("page","1").param("model","gpt-4o"))
   .andExpect(status().isOk()).andExpect(view().name("evaluation-coverage"))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("Dataset coverage"))).andReturn();
  var model=result.getModelAndView().getModel();
  assertThat(((Coverage)model.get("coverage")).records()).isEqualTo(24);
  assertThat(model.get("promptsUrl").toString()).contains("language=FIL", "q=paanyaya", "page=1");
  var links=(Map<?,?>)model.get("datasetLinks");
  assertThat(links.get(other).toString()).contains("/evaluation/"+other+"/coverage", "task=NLU", "language=FIL", "page=0", "model=gpt-4o");
  result=mvc.perform(get("/evaluation/"+id+"/models/gpt-4o").param("language","FIL"))
   .andExpect(status().isOk()).andReturn();
  links=(Map<?,?>)result.getModelAndView().getModel().get("datasetLinks");
  assertThat(links.get(other).toString()).contains("/models/gpt-4o", "language=FIL");
  mvc.perform(get("/evaluation/"+UUID.randomUUID()+"/coverage")).andExpect(status().isNotFound());
 }

 @Test void compositionPreservesCountsAcrossUnevenAndUnknownConditions() throws Exception {
  var full=Coverage.of(adapter.parse(sample()).records()).composition();
  assertThat(full.maximum()).isEqualTo(4);assertThat(full.unknown()).isZero();
  assertThat(full.groups()).hasSize(2);
  assertThat(full.groups().stream().mapToLong(Coverage.CompositionGroup::total).sum()).isEqualTo(24);
  var partial=Coverage.of(adapter.parse(Files.readAllBytes(Path.of("samples/synthetic-partial.json"))).records()).composition();
  var safe=partial.groups().stream().filter(g->g.context().equals("SAFE")).findFirst().orElseThrow();
  var nlu=safe.rows().stream().filter(r->r.task().equals("NLU")).findFirst().orElseThrow();
  var nlr=safe.rows().stream().filter(r->r.task().equals("NLR")).findFirst().orElseThrow();
  assertThat(nlu.total()).isEqualTo(2);assertThat(nlr.total()).isEqualTo(1);
  assertThat(nlu.englishWidth()+nlu.filipinoWidth()).isEqualTo(100.0);
  assertThat(nlr.englishWidth()+nlr.filipinoWidth()).isEqualTo(50.0);
  var unknown=new ObjectMapper().createObjectNode().put("context_condition","UNKNOWN").put("task_type","NLU").put("language_variant","FIL").put("base_query_id","U1").put("context_id","U1_C1");
  var u=Coverage.of(List.of(unknown)).composition();
  assertThat(u.unknown()).isEqualTo(1);
  assertThat(u.groups().stream().filter(g->g.context().equals("UNKNOWN")).findFirst().orElseThrow().total()).isEqualTo(1);
  assertThat(Coverage.of(List.of()).composition().groups().stream().flatMap(g->g.rows().stream()))
   .allMatch(r->Double.isFinite(r.englishWidth())&&Double.isFinite(r.filipinoWidth()));
 }
}
