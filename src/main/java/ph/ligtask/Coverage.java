package ph.ligtask;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
public record Coverage(int records,int bases,int contexts,long missingContext,List<Cell> cells,List<String> missingVariants) {
 public record Cell(String context,String task,String language,long count){}
 public record CompositionRow(String task, long english, long filipino, long maximum) {
  public long total() { return english + filipino; }
  public double englishWidth() { return english * 100.0 / maximum; }
  public double filipinoWidth() { return filipino * 100.0 / maximum; }
 }
 public record CompositionGroup(String context, long total, List<CompositionRow> rows) {}
 public record Composition(long maximum, long unknown, List<CompositionGroup> groups) {}

 /** Counts share one scale, so short bars expose incomplete or unbalanced cells. */
 public Composition composition() {
  long maximum = 1;
  for (String context : List.of("SAFE", "UNSAFE", "UNKNOWN")) {
   for (String task : List.of("NLU", "NLR", "NLG")) {
    maximum = Math.max(maximum, count(context, task, "EN") + count(context, task, "FIL"));
   }
  }
  List<CompositionGroup> groups = new ArrayList<>();
  long unknown = cells.stream().filter(cell -> cell.context().equals("UNKNOWN")).mapToLong(Cell::count).sum();
  for (String context : List.of("SAFE", "UNSAFE", "UNKNOWN")) {
   if (context.equals("UNKNOWN") && unknown == 0) continue;
   List<CompositionRow> bars = new ArrayList<>();
   for (String task : List.of("NLU", "NLR", "NLG")) {
    bars.add(new CompositionRow(task, count(context, task, "EN"), count(context, task, "FIL"), maximum));
   }
   groups.add(new CompositionGroup(context, bars.stream().mapToLong(CompositionRow::total).sum(), bars));
  }
  return new Composition(maximum, unknown, groups);
 }
 private long count(String context, String task, String language) {
  return cells.stream().filter(cell -> cell.context().equals(context) && cell.task().equals(task)
    && cell.language().equals(language)).mapToLong(Cell::count).sum();
 }
 public static Coverage of(List<JsonNode> rows){
  Set<String> bases=new TreeSet<>(),contexts=new HashSet<>(),observed=new HashSet<>(); List<Cell> cells=new ArrayList<>();List<String> missing=new ArrayList<>();
  for(JsonNode r:rows){String b=r.path("base_query_id").asText();bases.add(b);contexts.add(b+"|"+r.path("context_id").asText());observed.add(b+" / "+r.path("context_condition").asText()+" / "+r.path("task_type").asText()+" / "+r.path("language_variant").asText());}
  for(String c:List.of("SAFE","UNSAFE","UNKNOWN"))for(String t:List.of("NLU","NLR","NLG"))for(String l:List.of("EN","FIL")){
   cells.add(new Cell(c,t,l,rows.stream().filter(r->r.path("context_condition").asText().equals(c)&&r.path("task_type").asText().equals(t)&&r.path("language_variant").asText().equals(l)).count()));
   if(!c.equals("UNKNOWN"))for(String b:bases){String v=b+" / "+c+" / "+t+" / "+l;if(!observed.contains(v))missing.add(v);}
  }
  return new Coverage(rows.size(),bases.size(),contexts.size(),rows.stream().filter(r->r.path("context_text").asText("").isBlank()).count(),cells,missing);
 }
}
