# Numeric metric exports

On Prepared prompts or a model-results page, choose **Export metrics**. Exports
include all seven study models even when opened from one model's page. Current
task/language/context/search filters apply to the entire matching dataset, not
just the displayed page. Human classifications use the confirmed task gold.label mapping.

- **Download model CSVs (ZIP):** combined `metrics.csv`, seven `models/*.csv`
  files, `manifest.json`, and a short README. No notebook or checking script.
- **Download combined CSV:** the same numeric rows in one file, grouped by
  `model_code`. Download the codebook separately to retain identity/provenance.
- **Download codebook:** model/factor/status encodings, prompt index mappings,
  dataset version, selected per-model run IDs, provider IDs, settings and filters.

CSV data cells contain only numbers or empty fields. No prompt/context/policy
text, explanations or raw responses are included. UUIDs and other textual
identifiers are retained outside the CSV in the codebook. Numeric prompt indexes
are stable within the dataset version and do not change with pagination/filtering.

| Column | Meaning |
|---|---|
| prompt_index | 1-based prompt row in the dataset's instance-ID ordering |
| base_query_index | 1-based base-query code; codebook maps to original identity |
| model_code | 1–7 in the codebook's fixed model order |
| task_code | 1 NLU; 2 NLR; 3 NLG |
| language_code | 0 English; 1 Filipino |
| context_code | 0 SAFE; 1 UNSAFE; 2 UNKNOWN |
| status_code | 0 not evaluated; 1 valid; 2 invalid; 3 HTTP error; 4 uncertain; 5 cancelled; 6 pending; 7 in flight |
| valid_response | 1 only for VALID saved responses; otherwise 0 |
| reused | 1 if the selected run reused a previously saved attempt |
| human_label | Binary human reference from task gold.label; blank for missing or unrecognized task labels |
| model_label | 0 Safe / Does Not Violate Policy / Answer; 1 Unsafe / Violates Policy / Refuse |
| human_safety_rating | Mean human safety score from gold.rating_mean; missing or invalid scores are empty |
| model_safety_rating | Valid model's 1–10 safety rating, higher = safer |
| positive_probability | Model self-reported probability of binary class 1 (0–1); blank for legacy or missing responses |

Default run selection is the latest finished run **separately per model**, with
no 100-run history limit on this lookup. Only one run supplies a model's results;
newer partial runs are not backfilled from older runs. Alternatively select one
finished run explicitly for all models. Models without evaluations and prompts
outside that run still appear with status 0 and missing judgments. Failed/invalid
responses retain their status and do not supply numeric classification/ratings.
Repeating or reusing a run does not duplicate prompt/model rows in this export.

The read-only export transaction uses a repeatable-read snapshot. An explicit
run must belong to the selected dataset and must be finished. Exports never start
provider calls or require configured provider keys. The legacy `/export.csv`
endpoint is retained for existing text-based dataset verification; UI downloads
use `/metrics.csv`, `/metrics.zip`, and `/metrics-manifest.json` instead.

For Colab comparison, preserve each model's grouping, exclude nonvalid responses,
and use complete pairs appropriate to each metric. Empty fields are missing,
never zero. Human classification is read only from the confirmed gold.label field;
context, source_label, and binary_rating do not override that field.
A model rating is not a BCE probability. Accuracy/Precision/Recall/F1 are fractions
internally and percentages on the dashboard. PCC/BCE are unscaled; BCE uses natural
logs without hidden clipping. Undefined denominators are unavailable, with F1=0
when TP=0 and FP+FN>0. PCC requires two nonconstant paired score series.

Human means from gold.rating_mean supply PCC and human_safety_rating. The v2 contract supplies self-reported positive-class
probabilities for BCE; legacy responses remain blank. No statistical analysis method or dataset
schema is changed by this reference mapping.

The export dialog defaults to the run selected on the results page, when one is
selected. Changing the export selector can explicitly choose another finished run
or the latest finished run independently for each model.
