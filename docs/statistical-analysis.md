# Statistical analysis page

Open **Statistical analysis** in the dataset sidebar (`/evaluation/{dataset}/analysis`).
Choose one finished saved run. The page shows two seven-model summary tables:
correctness and model safety ratings. The seven effects are Context, Language,
Task, Context×Language, Context×Task, Language×Task, and Context×Language×Task.
These are separate analyses per model, not model-versus-model significance tests.
No model APIs are invoked. Completed, completed-with-errors, cancelled, and
interrupted runs can be inspected; an active run cannot be analyzed. Invalid or
missing observations are excluded. One complete base query has all 12 unique
SAFE/UNSAFE × EN/FIL × NLU/NLR/NLG cells for the selected outcome. Unknown context
is excluded. Models are not merged and responses from different runs are not pooled.

The default selection is the latest finished run among the 100 saved runs returned
by history. A specific run ID is checked against the dataset, including on export.
The top-level dataset chooser retains the analysis route. Complete-base counts
and exclusions are outcome-specific: a rating can be usable without a human label.

## Implemented plan: three-factor-v1

This operationalizes the accepted two-outcome presentation while accounting for
repeated queries. The statistical treatment in the manuscript should be updated
and reviewed before final research reporting; this software does not imply adviser
approval of a method. In particular, no ordinary ANOVA is performed on binary
correctness, and normalizing outcomes is not assumed to establish independence.

### Correctness

- One binary outcome per prompt: exact agreement of validated task classifications
  with finalized human references.
- Binomial-logit GEE; group on base query; independent working correlation with
  bias-reduced sandwich covariance; full Context×Language×Task mean structure.
- Sum-to-zero categorical contrasts: lower-order effects use centered factor
  coding rather than a single reference condition. Joint Wald chi-square test per
  term; df is the number of coefficients in the term (1 or 2).
- Require at least 30 complete base queries. Reject unestimable cases, including
  condition cells with constant correctness, nonconvergence, and singular covariance.
- **Confirmed reference mapping:** the Java loader reads the selected task's
  `gold.label` as the human classification, as confirmed by the dataset owner.
  It joins by persisted prompt identity within the chosen dataset and run.
  Unknown/missing task labels are excluded rather than inferred from context,
  source_label, or binary_rating. The immutable dataset ID identifies the
  reference snapshot. Sufficient complete valid pairs enable correctness tests;
  insufficient panels still show N/A. Human numerical means from gold.rating_mean feed PCC, separately from these model-rating analyses.

### Model safety ratings

- Use the saved valid 1–10 model safety rating, interpreted approximately as a
  continuous outcome. This assesses changes in model judgments, not alignment
  with human scores or actual safety of generated content.
- Balanced three-factor repeated-measures ANOVA. Each base query is the repeated
  unit. Orthonormal Helmert product contrasts isolate each of the seven effects.
- For an effect with q contrast dimensions and n base queries, let Z be its
  n×q projected score matrix. SS_effect = n||mean(Z)||²;
  SS_error = ||Z-mean(Z)||²; F = SS_effect(n-1)/SS_error.
- Greenhouse–Geisser epsilon = trace(S)²/(q trace(S²)), bounded to [1/q,1], where
  S is the covariance of the effect's contrast scores. Correct df1=q*epsilon and
  df2=(n-1)*q*epsilon before the F survival probability. For q=1 epsilon is 1.
- Report F, corrected dfs, epsilon, partial eta-squared, raw p, adjusted p, and
  condition means/SDs. Zero error variance is unavailable rather than p=0.
- Contrast-residual Shapiro–Wilk values are diagnostic only. The displayed value
  is the minimum over an effect's contrast dimensions. It is not a method-selection
  rule or proof that the ordinal-to-continuous approximation is appropriate.

### Shared rules

The minimum of 30 complete bases is a conservative application readiness policy,
not a power calculation or guarantee of valid assumptions. Complete-case exclusions
can introduce bias if failures/missingness are systematic. No imputation is performed.
The summary reports Holm-adjusted p-values with alpha .05 across the fixed 98-test
family (7 effects × 7 models × 2 outcomes). Unavailable tests retain a p=1 slot so
partial results do not shrink the planned family. Raw p-values remain in details.
Bold formatting indicates adjusted p<.05; it does not measure effect magnitude.
Omnibus interactions require careful interpretation before interpreting main
effects. Follow-up contrasts/post-hoc pairwise comparisons are not implemented;
the tables do not claim to identify which task pair differs.

## Local runtime

The worker is packaged in the JAR at `analysis/three_factor.py`. It receives only
identifiers, factors, response status, classification and ratings via JSON stdin;
it receives no prompt text or API keys as input. It writes JSON stdout. There is
no shell interpolation, network call, database write, or provider request.

Install once using Python 3.11–3.13 (default setup executable: python3.13):

```sh
sh scripts/setup-analysis
```

`analysis/requirements.txt` pins NumPy, SciPy, pandas and statsmodels. The default
runtime is `.local/analysis-venv/bin/python` relative to the project working
directory. Set `LIGTASK_ANALYSIS_SETUP_PYTHON` for another supported interpreter
during setup, or `LIGTASK_ANALYSIS_PYTHON` to the absolute installed runtime path
when launching Java. The worker records actual library versions in computed reports.
Missing dependencies, failed fits and timeouts never produce fake numeric results.

The Java bridge uses private temporary files for the packaged script/report,
limits computation to 45 seconds, and cleans up afterward. Incomplete samples do
not start Python. The page can therefore explain missing data before dependencies
are installed. GET `/analysis.json?run={uuid}` exports the same plan, per-model
counts, results, dataset/run IDs and generation time, with cache-control no-store.
Results are recomputed from the chosen immutable saved observations; they are
not written back as a separate analysis version in PostgreSQL.

## Validation

```sh
.local/analysis-venv/bin/python -m unittest discover -s src/test/python -v
JAVA_HOME=/path/to/jdk scripts/mvn -o verify
```

Synthetic tests cross-check every ANOVA F and uncorrected df against statsmodels
AnovaRM, compare the two-level context test with paired t², exercise the binary
GEE path, check the 98-test adjustment, exclusions, missing references, small
samples, duplicate cells, separation, and zero variance. Java tests cover HTML,
JSON export, foreign/active run rejection, and the packaged Java/Python bridge.
The bridge integration test requires the local interpreter installed above.

References:
- [statsmodels GEE](https://www.statsmodels.org/stable/gee.html)
- [GEE covariance options](https://www.statsmodels.org/stable/generated/statsmodels.genmod.generalized_estimating_equations.GEE.html)
- [statsmodels AnovaRM](https://www.statsmodels.org/stable/generated/statsmodels.stats.anova.AnovaRM.html)
