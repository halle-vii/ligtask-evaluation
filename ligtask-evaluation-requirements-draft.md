# LIGTask Evaluation Application — Requirements Planning Draft

Status: provisional planning document, not an approved implementation specification.

## 1. Purpose and evidence

Build the evaluation portion of LIGTask: accept a prepared LIGTask Safety Matrix (LSM), preserve human reference labels, obtain or import model outputs, compare results, and export reproducible evaluation data.

The manuscript’s Assessment and Evaluation sections (printed pp. 73–76), research instruments (pp. 77–79), and sampling/statistical treatment (pp. 80–85), together with the supplied architecture image, establish the research context. Recommendations below are implementation proposals; they are not all existing manuscript requirements. The Figma Evaluation section (60:31) has now been inspected through structural metadata and screenshots, with larger screenshots of Specific LLM (60:416) and Specific Prompt (108:189). The review covers these visible designs, not every page or prototype interaction in the file.

Design reference: https://www.figma.com/design/alHz7X2YxjRwABZxct1zmB/LIGTASK-App?node-id=60-31

## 2. Scope boundary

Include prepared-dataset import and validation, model-result ingestion, ground-truth ingestion and versioning, classification, evaluation metrics, comparisons, exports, and experiment provenance.

Exclude CASE-Bench collection, Context Integrity Parsing, Semantic Intent Extraction, Task Frame Generation, translation, translation verification, model training, and fine-tuning. These produce upstream inputs.

Confirmed user choices:

- Use Java with Spring Boot.
- Human annotation is a separate process for the initial application. Import its outputs; leave room for future integration.
- Interface/deployment is undecided. The latest user answer supersedes the earlier desktop preference.

One execution-scope decision remains open: does this application invoke the models, import externally generated responses, or support both? Recommended target: invoke selected models and also accept imported results for testing and externally executed experiments; this is a proposal pending confirmation.

Provisional build sequence: establish dataset/annotation/result contracts and import-based evaluation, then add model execution if confirmed. Annotation collection screens are a future extension and are outside the initial release. This sequence does not remove the research requirement to obtain model outputs and human reference labels.

## 3. Technology proposal

Spring Boot is confirmed. Recommended deployment: a browser interface backed by Spring Boot running locally on the researcher's laptop, with PostgreSQL storing the experiment data. Opening the interface through localhost does not require public hosting. Model API calls still require network access when remote models are used.

For a small thesis tool, server-rendered pages are a reasonable starting point; a separate frontend can be selected after inspecting Figma and discussing team skills. Keep domain and evaluation logic outside UI controllers so a future interface can reuse it.

Use PostgreSQL for related, versioned records: datasets, instances, annotations, runs, outputs, and reports. Keep this as one application with background jobs if model execution is included. Avoid introducing microservices for the initial scope.

If an installed desktop interface is chosen later, JavaFX can communicate with the Spring Boot service, but packaging must then account for both the application service and database. This adds distribution work and is not the recommended initial setup. PostgreSQL requires a database service in either arrangement. Local startup, configuration, backup, and shutdown instructions are part of the deliverable.

The manuscript currently names Python libraries and local deployment of large open-weight models. Align its software section with the final Java architecture. Separately verify where each model will run; the listed laptop specifications do not establish feasibility of local inference. Never silently replace a named research model with a newer or smaller model.

Spring Boot database reference: https://docs.spring.io/spring-boot/reference/data/sql.html

## 4. Research decisions to resolve

### D1 — Unit of sampling and prompt count

The manuscript states 1,200 instances from 100 pairs, but three tasks times two languages produces six instances per query-context pair. A design with 100 base queries, each having two contexts, produces 100 × 2 × 3 × 2 = 1,200 instances. A design with 100 query-context pairs produces 600. Define base query, context instance, and prompt instance distinctly before freezing counts.

### D2 — Ground truth and output coding

Distinguish an expected decision about an input from a human classification of an actual model response. The Assessment section describes input annotations, while an Assumptions passage describes coding model outputs. These are separate records and procedures.

Define the NLU and NLR response formats and parser rules. For NLG, decide whether the model actually generates a response that is subsequently coded as Answer/Refuse, or merely predicts one of those labels. The latter measures a decision rather than actual generation behavior.

If actual NLG responses are generated, define a rubric for partial answers, mixed refusal/compliance, irrelevant responses, empty responses, and ambiguous cases. Decide whether humans or a separate judge perform output coding. Store the coding source and rubric version. Do not infer refusal through a single keyword search, and do not convert provider errors into refusals.

An Answer/Refuse label alone does not assess the safety or quality of answer content. If the thesis claims harmful-content safety, its rubric must address partial or unsafe compliance explicitly.

### D3 — Label encoding and positive class

Proposed encoding, pending approval:

| Task | Code 0 | Code 1 |
|---|---|---|
| NLU | Safe | Unsafe |
| NLR | Does Not Violate Policy | Violates Policy |
| NLG | Answer | Refuse |

Keep expected label, observed label, contextual condition, and correctness in distinct fields. Code 1 does not mean an incorrect or unsafe model action. Correctness is equality between the expected and observed task labels. Report task-specific class names alongside numeric codes.

For NLG: expected Answer plus observed Refuse is over-refusal; expected Refuse plus observed Answer is failure to refuse. Call the latter unsafe compliance only if the response-coding rubric supports that interpretation.

### D4 — Ground-truth aggregation and agreement

Preserve each original annotator vote. Decide between the manuscript’s three expert annotators and twelve non-experts, including the required number per instance. Configure this rather than hard-coding it.

Define the final binary aggregation rule, tie handling, minimum vote count, and adjudication. A proposed majority-vote rule is not yet an approved manuscript requirement. Preserve the fraction of code-1 votes separately from the finalized binary label.

Compute nominal Krippendorff’s alpha from independent votes before adjudication. A single finalized label per instance is insufficient to compute agreement. Verify the manuscript’s observed-disagreement formula against the standard definition; as printed, the binary disagreement expression appears to omit the symmetric contribution. Do not copy it directly into code.

Reference: https://www.asc.upenn.edu/krippendorffs-alpha-reliability

### D5 — Binary versus score-based evaluation

Accuracy, precision, recall, F1, and confusion matrices can use binary labels. PCC and BCE require a defined score/probability protocol for the intended score-based analysis. Do not fabricate probabilities from hard labels or describe self-reported confidence as token probabilities.

Choose the scoring method, score direction, normalization, target (final binary label or vote proportion), and threshold before enabling those metrics. Record score provenance. The manuscript’s binary-target BCE differs from CASE-Bench’s soft human-rating formulation; choose and document which is intended.

CASE-Bench omits PCC and BCE for binary-only prompting: https://arxiv.org/html/2501.14940v2#S5.SS2

Binary log-loss reference: https://scikit-learn.org/stable/modules/generated/sklearn.metrics.log_loss.html

### D6 — Inferential analysis

The manuscript proposes Context × Language × Task analysis, factorial ANOVA, Tukey HSD, and alpha = 0.05. The same base query contributes multiple related observations; ordinary independent-observation analysis must not be assumed valid. Scaling metrics alone does not establish normality or independence.

Define the outcome, unit of analysis, replication, within-query dependence, model handling, assumptions, and comparison procedure with the adviser/statistical reviewer. Twelve aggregate cell scores alone are not independent replication for a fully interacted factorial model. Do not pool different metrics as interchangeable observations or treat the seven evaluated models as interchangeable replicates without a justified design.

Preserve base-query IDs and export analysis-ready rows now. Implement inferential analysis after the procedure is settled; repeated-measures approaches or clustered binary-outcome models may be appropriate, depending on the outcome.

References: https://www.statsmodels.org/stable/anova.html and https://www.statsmodels.org/stable/gee.html

## 5. Proposed functional requirements

| ID | Requirement | Acceptance criterion |
|---|---|---|
| FR-01 | Import versioned prepared LSM data from a documented CSV or JSON format. | Invalid enums, missing identifiers/text, and duplicates are listed by row; no invalid dataset is silently accepted. |
| FR-02 | Preserve base-query relationships across context, task, and language variants. | The application reports observed counts for all 12 factor combinations and identifies missing variants against a selected expected design. |
| FR-03 | Import independent votes or finalized ground truth with provenance. | Every reference label maps to a known dataset instance; alpha is unavailable with a clear reason if individual votes are absent. |
| FR-04 | Version ground truth and aggregation rules. | Existing reports continue to reference their original label version after a revision. |
| FR-05 | Register model and experiment configuration. | Each run identifies exact model/version, dataset version, selected instances, prompt-template version, parameters, and execution or import source. |
| FR-06 | Ingest model outputs without changing the prepared evaluation text. | Raw response, task label, score when supplied, and provenance remain inspectable for each result. |
| FR-07 | Separate execution status from observed labels. | Timeouts, API errors, malformed outputs, and unresolved classifications appear as separate counts rather than Safe/Unsafe or Answer/Refuse labels. |
| FR-08 | Compute binary metrics per model and task, with language/context filters. | Reports show confusion counts, accuracy, per-class precision/recall/F1, proposed macro-F1, and the number of eligible and excluded instances. |
| FR-09 | Explain undefined metrics. | Zero-denominator precision/recall and insufficient-data cases display N/A with a reason under a documented policy. |
| FR-10 | Support score-based metrics only with a valid protocol. | PCC is unavailable for constant/insufficient series; BCE validates score range, direction, target type, and documented numerical clipping. |
| FR-11 | Compare models on a common eligible set by default. | Comparison reports list matched sample counts and exclusions; separately available-data summaries are clearly identified. |
| FR-12 | Inspect individual errors and disagreements. | A researcher can trace a report row to the prepared prompt, reference votes/label, raw model output, classification, and relevant versions. |
| FR-13 | Export detailed results and summaries. | CSV/JSON exports include identifiers, factors, raw/derived result references, version information, metric denominators, and exclusions sufficient to reproduce the report. |

Conditional requirements if model execution is included:

- FR-14: Execute zero-shot requests through configurable provider adapters; approved evaluation prompts contain no demonstrations or reference labels.
- FR-15: Run requests in the background with progress, cancellation, bounded retries, and resumable pending work. Preserve every attempt and identify the selected result deterministically. Warn that a retry after an uncertain provider response can incur another charge.
- FR-16: Preview request count and estimated cost where pricing is configured. Any unsupported or unavailable named model must be reported explicitly.

Future extension requirements for annotation collection; excluded from the initial release:

- FR-17: Authenticate annotators, assign instances, save independent votes, and track completion. Hide other annotators’ votes and model outputs during input ground-truth annotation.
- FR-18: Provide separate adjudication and response-coding workflows with audit history; original votes remain intact.

## 6. Minimum data contract and proposed entities

### Observed dataset sample — pending actual JSON

Reviewed the 58-page `LIGTask_English_Filipino_Side_by_Side (1).pdf`. It presents 300 unique paired entries across 100 base IDs. Each base ID has one NLU, one NLR, and one NLG entry. All identifiers carry SAFE; this PDF contains no UNSAFE identifier variants. Each entry has English and original Filipino text; 233 entries also have a revised Filipino text and 67 state that no revision was supplied.

These counts establish 300 task entries with two language views, potentially 600 evaluation instances if one Filipino version per entry is selected. Original and revised Filipino versions are translation history, not automatically separate benchmark instances. This is a partial view of the intended 1,200-instance design, not evidence that the complete dataset has only safe contexts.

The PDF is a translation comparison export, not the final import schema. Full contextual integrity fields, human votes, finalized ground truth, review approval status, and execution wrappers are not shown. Their absence here does not establish that they are absent from the colleague's JSON. The source CSV name mentions Supabase; this does not require the evaluation app to use Supabase or connect to the source database.

Sample-driven import requirements:

- Preserve the upstream entry ID (e.g. CB_001_SAFE_NLU) and base ID separately. Derive or read task/context metadata with validation; do not interpret SAFE in an ID as finalized human ground truth.
- Give English and Filipino evaluation instances distinct internal identities while keeping the same upstream entry relationship. Language is not encoded in these PDF entry IDs.
- Preserve original and revised Filipino text, with an explicitly selected evaluation version and its review status. Proposed selection: an approved revision when available, otherwise an approved original. A revision's presence is not evidence of approval. Until approval information is available, report status as unknown rather than inventing it.
- Freeze the selected text and version for each run. Later revisions must not silently change historical experiments. Do not run both versions or regenerate a translation unless a separate experiment specifies that behavior.
- Show partial dataset coverage; support preview/import of this sample without declaring it a complete 2 × 2 × 3 benchmark. Verify requirements for complete official runs separately.
- Read complete context from the JSON or a linked input. The prompt text alone and SAFE suffix are insufficient to reconstruct the intended safe contextual condition.
- NLU examples are descriptions of a user request, NLR examples ask for an appropriateness decision, and NLG examples directly request a response. Specify the exact task instruction/context wrapper around these texts and the response extraction procedure. Do not assume an NLU description alone asks a model to output Safe/Unsafe.

Do not finalize JSON property names, nesting, or null-handling until the actual export is inspected. Map its structure to the domain contract rather than requiring the colleague's export to match invented keys.

Dataset instances need: dataset_version, instance_id, base_query_id, context_id, context_condition, task_type, language_variant, prepared_prompt_text, and the context/policy text actually used. Preserve upstream category, intent, and CI metadata when supplied, without regenerating them.

Annotation records need: instance_id, pseudonymous annotator_id, task_label, timestamp, annotation/rubric version, and source. Final ground truth needs its version, aggregation method, vote count, vote proportion when available, and adjudication provenance.

Model results need: run_id, instance_id, exact model identifier, raw_response, execution_status, observed_task_label, parser/coder version, optional score, score_method/direction, and attempt metadata. Imported results require source provenance; unavailable metadata must remain explicitly unknown.

Proposed entities: DatasetVersion, BaseQuery, ContextInstance, PromptInstance, Annotation, GroundTruthVersion, GroundTruthLabel, ModelConfiguration, EvaluationRun, ResponseAttempt, OutputClassification, EvaluationReport, and AnalysisRun. Add User/Role entities if authentication is included.

Unique constraints must prevent duplicate instance variants within a dataset, duplicate independent votes within a defined annotation round, and accidental duplicate selected results for one run/model/instance/repetition. Foreign keys must reject unmatched annotations/results.

## 7. Proposed screens and operational requirements

Observed Figma screens and their requirements:

| Screen | Observed design | Requirement refinement |
|---|---|---|
| Dashboard (60:35) | Drag/drop or Browse, uploaded JSON filename, Submit. | Validate the prepared LSM schema and show errors/readiness before submission. The placeholder CASE-Bench filename does not authorize rebuilding upstream processing. Define whether labels arrive within this file or through a separate import. |
| Evaluation overview (55:22) | Seven model rows; Accuracy, Precision, Recall, F1, PCC, BCE columns; Overview/NLU/NLR/NLG selectors; Overview/Prompts navigation. | Identify the dataset and run represented, calculate values from stored results, and support drill-down to a model. Specify pooling/averaging for the Overview view before implementation. Placeholder figures are not acceptance targets. |
| Specific LLM (60:416) | Metric cards; language, context, and task filters; query-result table; Truth Label, Human Label, LLM Label, Match/Mismatch; Export CSV. A search bar is placed within the section near this view. | Filters must update metric cards, row counts, table rows, and exports consistently. Define search fields, an All/reset option, and pagination. Preserve full instance identifiers even when showing a shorter base ID. |
| Alternate model results layout | English/Filipino tabs, NLU/NLR/NLG buttons, metric cards, human/model result table, Export CSV. | Treat this as an alternative layout until the preferred model-results design is selected; do not build duplicate screens with contradictory filter behavior. |
| Specific Prompt (108:189) | Instance navigation, base ID, language, task, truth value, category, query, context attributes, human/model labels, match result. | Display full prompt/context text, selected model/run, reference-label provenance, raw model response, and classification provenance. Returning to the table should preserve filters. |

Additional proposed workflow screens/states, not established by the reviewed designs: ground-truth import/readiness and agreement; model/run setup and run progress if execution is included; loading, empty, failed, invalid-import, missing-ground-truth, and unavailable-metric states. Annotation collection and adjudication remain future extensions.

Figma decisions and discrepancies to resolve:

- Define Truth Label versus Human Label. If truth denotes the upstream context/designation, rename it to Context Condition or Source Label and keep it distinct from human ground truth. If it means finalized ground truth, identify whether Human Label means an individual vote, an aggregate, or a redundant display. Select exactly one authoritative reference for each reported comparison.
- The mockup uses Safe/Unsafe for NLR and NLG as well as NLU. Choose either task-native labels. The screen must not obscure Answer/Refuse semantics.
- Define the averaging method and scale for displayed metrics. If precision and recall are both 72.9% for the same class/aggregation, their harmonic-mean F1 is 72.9%, not the displayed 92.0. An alternative aggregation would need an explicit label. PCC/BCE must show N/A with a reason when their protocol is unavailable.
- Nearby design notes mention precomputed inter-annotator agreement, annotator criteria/filtering, human ratings from 1 to 10, future users, and a possible context toggle. These are design proposals, not confirmed requirements. Decide whether agreement is imported or recomputed from votes. A 1–10 rating scale would change the annotation contract and score-based methodology.
- If a context toggle only shows/hides context in the interface, it must not change the experiment. Removing context from model input creates a different run/condition and needs a separately defined research protocol.

The app must preserve English and Filipino text using UTF-8, support the selected Windows/macOS deployment, store secrets outside exports and browser-delivered content, and provide database backup/restore instructions. Reports must record calculation versions and filters. Dataset imports must be transactional, and completed experiment records must be preserved when later datasets or labels change.

Proposed capacity target: at least 1,200 prompt instances and 8,400 selected model results for seven models with one response per instance, plus annotation records and retry attempts. This target depends on resolving D1; repetitions and separate score runs increase it. Background operations must not block navigation. Establish measurable response-time targets after deployment and hardware are chosen.

Verify metric calculations against hand-checked cases and a trusted reference implementation. Include failures/missing outputs, undefined metrics, duplicated imports, version preservation, and comparison-set consistency in acceptance checks.

## 8. Delivery sequence and instruction to the future coding agent

1. Confirm model execution scope and the recommended local browser deployment; resolve sampling unit, response-coding protocol, and ground-truth aggregation.
2. Define example input files and expected reports, then finalize requirement IDs and acceptance criteria.
3. Build dataset/label/result imports, provenance, basic metrics, and CSV/JSON exports.
4. Add model execution if confirmed. Keep annotation collection as a separately scoped future extension.
5. Add approved score-based and inferential analysis and match the verified Figma screens.

The coding agent must treat unresolved decisions as unresolved. It must not add upstream preprocessing, change research models, manufacture probabilities, infer human ground truth from model outputs, or claim an inferential procedure is valid without the specified analysis design. A working import-based path can be developed independently of live provider access, but does not itself complete a scope that includes live execution.
