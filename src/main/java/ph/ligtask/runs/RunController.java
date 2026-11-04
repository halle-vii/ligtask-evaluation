package ph.ligtask.runs;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.*;
import ph.ligtask.DatasetStore;
import java.util.*;

@RestController
@RequestMapping("/evaluation/{dataset}/runs/api")
public class RunController {
    private final RunCoordinator coordinator;private final RunStore store;private final DatasetStore datasets;
    public RunController(RunCoordinator coordinator,RunStore store,DatasetStore datasets){this.coordinator=coordinator;this.store=store;this.datasets=datasets;}
    @PostMapping public Map<String,Object> start(@PathVariable UUID dataset,@RequestBody RunCoordinator.Start request){
        UUID id=coordinator.start(dataset,request);return state(dataset,id);
    }
    @GetMapping public Map<String,Object> history(@PathVariable UUID dataset){
        dataset(dataset);return Map.of("runs",store.history(dataset),"activeRun",store.active().map(UUID::toString).orElse(""));
    }
    @GetMapping("/{run}") public Map<String,Object> state(@PathVariable UUID dataset,@PathVariable UUID run){
        var result=new LinkedHashMap<String,Object>();result.put("run",require(dataset,run));result.put("counts",store.counts(run));return result;
    }
    @GetMapping("/{run}/responses") public Map<String,Object> responses(@PathVariable UUID dataset,@PathVariable UUID run,@RequestParam(defaultValue="0") int page){
        require(dataset,run);if(page<0||page>1400)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page.");
        return Map.of("rows",store.results(run,page),"page",page,"pageSize",50);
    }
    @PostMapping("/{run}/cancel") public Map<String,Object> cancel(@PathVariable UUID dataset,@PathVariable UUID run){
        require(dataset,run);store.cancel(run);return state(dataset,run);
    }
    @GetMapping("/{run}/retry-plan") public Map<String,Object> retryPlan(@PathVariable UUID dataset,@PathVariable UUID run){
        require(dataset,run);return coordinator.retryPlan(dataset,run);
    }
    @PostMapping("/{run}/retry") public Map<String,Object> retry(@PathVariable UUID dataset,@PathVariable UUID run,@RequestBody RunCoordinator.Retry request){
        require(dataset,run);return state(dataset,coordinator.retry(dataset,run,request));
    }
    @DeleteMapping("/{run}") public Map<String,Object> delete(@PathVariable UUID dataset,@PathVariable UUID run){
        require(dataset,run);return store.delete(dataset,run);
    }
    @ExceptionHandler(EmptyResultDataAccessException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String,String> missing(){return Map.of("message","Saved run not found in this dataset.");}
    @GetMapping("/{run}/export") public ResponseEntity<Map<String,Object>> export(@PathVariable UUID dataset,@PathVariable UUID run){
        var state=state(dataset,run);state.put("responses",store.export(run));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"ligtask-run-"+run+".json\"").body(state);
    }
    private Map<String,Object> require(UUID dataset,UUID run){try{return store.run(dataset,run);}catch(EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}}
    private void dataset(UUID id){try{datasets.dataset(id);}catch(EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> statusError(ResponseStatusException e){
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"Run request failed.":e.getReason()));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(IllegalArgumentException e){return Map.of("message",e.getMessage()==null?"Invalid run request.":e.getMessage());}
}
