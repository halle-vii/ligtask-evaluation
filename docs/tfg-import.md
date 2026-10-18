# TFG dataset adapter

Uploads accept only the main dataset's TFG structure. `DatasetImporter` first validates against the bundled [JSON schema](../src/main/resources/static/schemas/tfg-en-fil-v1.schema.json), then maps valid source arrays with `TfgJsonAdapter`. The schema is independent of the dataset file and database contents; no main JSON needs to be stored in the project. The existing imported data remains unchanged.

“Same structure” means matching field names, nesting and types, including all three task objects, their task-specific output formats and source gold metadata. Missing, renamed or extra fields are rejected at every object level. Different query text, base IDs, categories, record counts, JSON key order and formatting are allowed. Source factors, duplicate IDs and parent/task consistency are also checked.

## Observed structure

The supplied export contains 400 source entries: 100 base queries × 2 explicit context conditions × 2 query languages. Each entry has three task derivatives, yielding 1,200 application prompt instances and 100 instances in each of the 12 context/task/language cells. Every observed entry has structured context. Coverage measures combinations, not annotation readiness.

## Mapping

| Source | Application |
|---|---|
| `base_id` | `base_query_id` |
| `context_intended_to_be_safe` boolean | Context condition SAFE/UNSAFE; never a human label |
| Task derivative key and matching `task_code` | NLU/NLR/NLG |
| `language` en/fil | EN/FIL query variant |
| `task_derivatives.<task>.input.query` | Exact `prepared_prompt_text` |
| `task_derivatives.<task>.instruction` | Separate task instruction, visible in prompt detail |
| `task_derivatives.<task>.output_schema` | Expected output format, never a model response |
| `context` | Original structured JSON serialized into context text, with flattened display attributes |
| `category` | Supplied category in prompt detail |
| Entire original array | Dataset `raw_document` JSONB |
| Source entry excluding sibling derivatives, plus selected derivative | Per-record `source_entry` and `source_task`, preserving unknown fields |

The export has no dataset version, instance ID, context ID, or upstream task-entry ID. The adapter assigns name `LIGTask TFG English / Filipino`, version `sha256-<full file hash>`, and adapter identifier `tfg-en-fil-v1`. These identify the import and mapping, not a researcher-supplied release version. Generated IDs are `base_condition_task_language`, `base_condition`, and `base_condition_task`; `import_metadata` records their derived origin and the zero-based source entry index. No condition is inferred from ID text.

A changed file gets a different content version; an identical file cannot be imported twice. Whitespace-only changes also change the byte hash. Original source JSON values and exact query strings are preserved; JSONB does not retain original JSON whitespace or key order. Keep the original file for byte-for-byte provenance.

## Validation

The root must be a nonempty array of up to 3,333 entries within the 10 MB upload limit. Required fields and allowed types are defined in the schema, downloadable from the upload screen. Old provisional-v1 uploads and arbitrary JSON objects are rejected. Duplicate JSON keys, trailing JSON documents, invalid factors, conflicting parent/input metadata and duplicate generated combinations block the entire import.

The main export contains legitimate structural variations which remain supported: `context.transmission_principle.urgency` is optional, and `recipient.background` can be text or an object with `previous_experience`, `purpose_of_query` and `demographics`. Filipino task inputs require `source_query_en`; English task inputs omit it. Supplied `rating_mean` can be null or numeric. Missing/null context and incomplete context objects now fail validation instead of being accepted with a warning. Nothing is invented to repair an invalid source.

Validation failures appear in a dismissible modal on the upload page; the selected file is retained. Valid uploads navigate to the preview page and still require confirmation before import. Errors identify the source location, for example `$[0].task_derivatives.nlu.research_role: required field is missing.` An invalid preview creates no pending import and replaces any previous pending upload. Confirmation reparses/revalidates before saving; no records are saved on validation failure. At most 100 structure errors are shown per preview.

`ProvisionalJsonAdapter` remains an internal normalized-record validator and legacy test-fixture reader. It is not an alternate upload route. Accepted raw source values are preserved as JSONB; allowing new fields requires an intentional schema update.

Preview validates and counts the entire file, displaying the first 24 prepared records to keep the page manageable. Confirm imports every validated record atomically. PostgreSQL constraints and existing migrations already support this mapping; no database migration is necessary.

## Interpretation and remaining research inputs

The supplied `gold.reference_source` states that labels originate from the CASEBench contextual condition and must be replaced or confirmed through human annotation. All gold fields remain under `source_task.gold`; none populate human labels, model labels, or metrics. The app performs no model calls.

Filipino query variants have supplied English context and English task instructions. The adapter preserves these as received and does not claim that entire inputs are translated or approved. Source queries and `source_query_en` are retained separately; they are not substituted for task-specific queries. No explicit policy text, translation review/approval provenance, or independent annotation records were found. Those remain unknown.

Before execution/scoring, confirm the intended task protocol and language handling, policy source, annotation file/linking convention, final human references, and later model/run configuration. Human annotations remain external. Future imports should link to persisted prompt UUIDs or an explicit dataset version plus derived ID, and retain annotator/provenance information separately.

## Run the persistence check

After packaging and starting PostgreSQL, use the same database environment as the app. Omit `--file` to use the downloadable synthetic TFG sample:

```sh
python3 scripts/verify-persistence.py --file /path/to/LIGTask_TFG_dataset_en_fil_final.json
```

This imports through HTTP preview/confirmation on port 18080 (or reuses the matching import), compares every exported query, context, factor, and empty result label with the source, stops Java, restarts it against the same database, and repeats those checks. The imported dataset remains available to the normal app on port 8080.
