package ph.ligtask;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
public record DatasetDocument(String name, String version, String schemaVersion, boolean synthetic,
 JsonNode raw, List<JsonNode> records, List<String> errors, List<String> warnings) {
 public boolean valid(){return errors.isEmpty();}
}
