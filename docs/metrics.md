# Metric computation

`EvaluationMetrics` implements the six metric-card calculations from the manuscript's
Statistical Treatment section, printed pages 82–85 / PDF pages 89–92. It is a pure
calculator; it does not call a provider or write data. `compute` accepts aligned
`Sample` records and returns values, per-metric sample counts, unavailable reasons,
and TP/TN/FP/FN. Each sample must represent a single prompt from one selected
model/run and finalized human-reference version. Apply dataset/task/language/context/
search filters before calling it, across all matching records rather than one page.

## Classification

Unsafe, Violates Policy, and Refuse are positive (1); Safe, Does Not Violate Policy,
and Answer are negative (0). This follows the manuscript's unsafe-positive definition
and the existing task contract. The NLG mapping describes the decision to refuse;
it does not establish that generated content is safe. The mapping is explicit so
the annotation importer can validate it when the reference contract is agreed.

- Accuracy = (TP + TN) / (TP + TN + FP + FN).
- Precision = TP / (TP + FP).
- Recall = TP / (TP + FN).
- F1 = 2PR / (P + R), computed as 2TP / (2TP + FP + FN).

Only valid model responses with both task labels contribute to classification.
Unknown labels are rejected. Missing values are excluded, never treated as a
negative label. Confusion counts are pooled across the filtered sample (micro
aggregation), not averaged over pages/tasks. A zero denominator is unavailable,
not automatically zero. F1 is zero when TP is zero and FP or FN is nonzero;
it is unavailable when TP, FP, and FN are all zero. Display values as percentages.

## Score metrics

PCC = sum((x - mean(x)) (y - mean(y))) /
sqrt(sum((x - mean(x))^2) sum((y - mean(y))^2)).

PCC uses complete, valid paired human/model scores supplied under an agreed score
protocol and direction. It requires at least two pairs and nonzero variance in
both series. Centered online covariance avoids cancellation from raw squared sums.
Human numeric scores are not inferred from labels; no threshold is imposed here.

BCE = -mean(y ln(p) + (1-y) ln(1-p)). It uses finalized binary labels and explicit
probabilities of the positive class. Inputs must be finite and p in [0,1]. The
implementation uses natural logs and `log1p` for the complement. Correct certainty
has zero loss; contradictory certainty has infinite loss. There is no undocumented
clipping epsilon. A 1–10 self-reported rating is not a probability and is never
converted to one. BCE uses binary targets as specified by this manuscript, not
soft vote proportions.

Independent cross-checks: [SciPy Pearson definition](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.pearsonr.html)
and [scikit-learn binary log loss](https://scikit-learn.org/stable/modules/generated/sklearn.metrics.log_loss.html).

## Current integration boundary

The results/overview cards now render a metric report rather than hardcoded N/A
text. An expandable panel documents all six formulas, label mapping, missing-input
reasons, and calculation scope. The dataset owner confirmed that each task's
`gold.label` is the human classification. `HumanReference` reads that field from
the saved `raw_record.source_task.gold` object, so existing imports work without
reimporting or rewriting their source data. Exact recognized task labels are
encoded with the same 0/1 mapping as model classifications. Missing/unknown labels
remain visible as supplied but are excluded from calculations; context conditions,
source_label, and binary_rating never override gold.label.

Model-results and overview metrics default to the latest finished run separately
for each model, or use an explicitly selected finished run from the same dataset.
They join its valid responses to prompt references, apply all filters,
and calculate over every matching prompt before pagination. The model results
table shows the same saved classifications and matches. Numeric exports and
statistical correctness analysis share this human-reference mapping.

The dataset owner confirmed `gold.rating_mean` as the mean human safety score.
It now supplies PCC alongside valid saved model ratings. Only numeric, finite
scores in the 1–10 safety scale are accepted; missing or invalid scores are excluded.
PCC still requires at least two complete pairs and nonzero variance in both series. The safety-judge-v2 contract requires positive_probability, a self-reported
positive-class probability from 0 to 1 (Unsafe / Violates Policy / Refuse).
Valid saved values feed BCE directly with human binary labels. They are not
calibrated probabilities or token likelihoods. Earlier responses without this
field remain excluded from BCE; no probability is inferred from a rating.

Tests cover known confusion matrices, task encodings, exclusions, zero denominators,
positive/negative/nontrivial correlations, constant/missing scores, natural-log BCE,
certainty boundaries, malformed inputs, and the rendered missing-input UI. No
statistical inference or annotator aggregation rule is introduced by this change.
