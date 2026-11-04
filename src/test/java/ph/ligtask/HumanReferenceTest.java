package ph.ligtask;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HumanReferenceTest {
    private Map<String,Object> prompt(String task,String label) {
        return Map.of("task_type",task,"context_condition","UNSAFE","raw_record",Map.of("source_task",Map.of("gold",Map.of("label",label,"binary_rating",1,"rating_mean",9,"reference_source","Synthetic annotation"))));
    }
    @Test void usesConfirmedTaskGoldLabelAndIgnoresContextAndBinaryRating() {
        for(String task:List.of("NLU","NLR","NLG")) for(int code=0;code<2;code++) {
            String label=ph.ligtask.llm.LigtaskOutputContract.labels(task).get(code);
            var ref=HumanReference.from(prompt(task,label));
            assertThat(ref.label()).isEqualTo(label);assertThat(ref.canonicalLabel()).isEqualTo(label);
            assertThat(ref.binary()).isEqualTo(code);assertThat(ref.source()).isEqualTo("Synthetic annotation");
            assertThat(ref.safetyRating()).isEqualTo(9.0);
        }
    }
    @Test void supportsStoredJsonAndKeepsWrongTaskLabelsVisibleButNotEncoded() throws Exception {
        var original=prompt("NLG","Safe");
        var stored=new HashMap<>(original);stored.put("raw_record",new ObjectMapper().writeValueAsString(original.get("raw_record")));
        var ref=HumanReference.from(stored);
        assertThat(ref.label()).isEqualTo("Safe");assertThat(ref.binary()).isNull();assertThat(ref.canonicalLabel()).isNull();
    }
    @Test void missingLabelIsNotInferredFromContextOrAnotherField() {
        var row=Map.<String,Object>of("task_type","NLU","context_condition","SAFE","raw_record",Map.of("source_task",Map.of("gold",Map.of("source_label","Safe","binary_rating",0,"rating_mean",9))));
        assertThat(HumanReference.from(row).label()).isNull();
        assertThat(HumanReference.from(row).safetyRating()).isEqualTo(9.0);
        assertThat(HumanReference.from(Map.of("task_type","NLU")).binary()).isNull();
    }
    @Test void acceptsFractionalMeanButExcludesMissingMalformedAndOutOfRangeScores() {
        for(Object score:Arrays.asList(7.25,1,10,null,"8",0,11,Double.NaN,Double.POSITIVE_INFINITY)) {
            var gold=new HashMap<String,Object>();gold.put("rating_mean",score);
            var row=Map.<String,Object>of("task_type","NLU","raw_record",Map.of("source_task",Map.of("gold",gold)));
            Double expected=score instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue()>=1 && n.doubleValue()<=10 ? n.doubleValue() : null;
            assertThat(HumanReference.from(row).safetyRating()).isEqualTo(expected);
        }
    }
}
