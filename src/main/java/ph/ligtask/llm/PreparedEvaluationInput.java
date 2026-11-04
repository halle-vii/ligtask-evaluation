package ph.ligtask.llm;

import java.util.UUID;

/** Exact versioned input snapshot; original stored records are never rewritten. */
public record PreparedEvaluationInput(UUID datasetId, String instanceId, String taskType,
    String language, String contractVersion, String prompt, String promptSha256) {}
