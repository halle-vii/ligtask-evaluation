# Temporary JSON contract — provisional-v1

This is a demonstration adapter, **not a demand that the real export use these keys**. Use UTF-8, a root object, and a nonempty `records` array (up to 10,000 records / 10 MB). Keys are case-sensitive; duplicate JSON keys and trailing documents are rejected. Unknown fields are retained in JSONB, not interpreted.

Root fields: `schema_version` exactly `provisional-v1`; nonblank string `name` and `version`; boolean `synthetic`; `records` array. Each dataset name/version and exact source SHA-256 can be imported once.

Every record requires nonblank string fields:

| Field | Meaning / allowed values |
|---|---|
| instance_id | Distinct evaluation instance identifier within the dataset |
| upstream_entry_id | Source task entry shared across its language variants |
| base_query_id | Base query relationship, independent of task/language |
| context_id | Supplied context identity; consistent for a base/condition |
| context_condition | SAFE, UNSAFE, UNKNOWN; explicit source metadata only |
| task_type | NLU, NLR, NLG |
| language_variant | EN, FIL |
| prepared_prompt_text | Exact selected evaluation text; whitespace and UTF-8 preserved |

`context_text` and `policy_text` accept string, null, or absence. Missing/blank context text produces a warning; nothing is reconstructed. UNKNOWN is required when the source condition is unknown, regardless of the spelling of an ID. SAFE/UNSAFE **never** become human labels. Partial datasets remain importable; observed coverage and missing combinations are shown.

One variant per dataset/base query/context condition/task/language is allowed in this provisional two-condition design. Original and revised translations belong in source metadata, not duplicate benchmark instances. If the real study defines repeated contexts per condition, revise this constraint with an explicit design and migration. Source entry IDs must refer consistently to one base/context/task relationship across languages.

Optional `translation` and `metadata` objects (and all other source keys) are preserved and inspectable in raw records. The sample uses `translation.original`, `revised`, `selected_version`, and `review_status`. This version does not automatically select, approve, translate, or validate translation provenance; the prepared prompt is explicitly supplied. Missing review status stays unknown. No annotation parser is active, even if source JSON contains label-shaped keys.

Samples:

- `synthetic-provisional-v1.json`: 2 base queries × 2 context conditions × 3 tasks × 2 languages = 24 instances; 2 records in each of 12 expected cells. Both context conditions are explicitly invented demo scenarios, unrelated to the real dataset. No labels are provided.
- `synthetic-partial.json`: 3 instances, 1 base query, 9 missing reference variants, 1 missing context text; still valid.
- `invalid-provisional.json`: duplicate instance ID, missing prompt text, invalid context/task/language values. Entire import must be blocked.

A complete reference grid is not evidence of complete official data, approved translations, annotation readiness, or model execution readiness.
