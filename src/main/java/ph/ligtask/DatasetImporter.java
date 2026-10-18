package ph.ligtask;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Select an explicit source adapter; never guess fields from identifier substrings. */
@Component
public class DatasetImporter {
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final TfgStructureValidator structure;
    private final TfgJsonAdapter tfg;

    public DatasetImporter(TfgStructureValidator structure, TfgJsonAdapter tfg) {
        this.structure = structure;
        this.tfg = tfg;
    }

    public DatasetDocument parse(byte[] bytes) {
        try {
            JsonNode root = mapper.readTree(bytes);
            var errors = structure.validate(root);
            if (!errors.isEmpty()) return new DatasetDocument("", "", "tfg-en-fil-v1", false, root,
                List.of(), errors, List.of());
            return tfg.parse(root, bytes);
        } catch (Exception e) {
            return new DatasetDocument("", "", "", false, null, List.of(),
                new ArrayList<>(List.of("Invalid JSON. Check syntax, duplicate keys, and trailing content.")), List.of());
        }
    }
}
