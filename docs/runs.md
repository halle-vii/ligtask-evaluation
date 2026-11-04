# Runs: execution and persistence

## Start locally

Export provider API keys in the terminal that launches Java. Keys are read from the environment; they are not stored in the database or browser. Launching the application only prepares the worker and applies the additive V3 migration. It does not send requests. Stop an older Java instance first: only one worker can hold the database advisory lock.

Open Runs, select configured models, leave the prompt count at **One prompt (test)**, optionally enter a specific instance ID, choose the output token limit, and acknowledge provider charges. **Start run** saves a complete validated plan before any provider request is sent. For 1,200 prompts and four models, selecting All means up to 4,800 requests. No dollar estimate or hard spend cap is implemented; use the provider's account/key budget controls as well. The one-prompt default is for small diagnostic runs.

## What works

- The current selected dataset is used; archived datasets must be restored before execution.
- A plan captures the exact prepared text, task, language, contract version and SHA-256 for every selected prompt/model pair.
- Start tokens make repeated submissions idempotent. A database constraint allows only one active run globally.
- Calls execute sequentially, normally at least 1.5 seconds apart, with no automatic retries.
- OpenRouter evaluation requests carry the shared strict JSON output schema and require supporting provider endpoints. Hugging Face currently uses the existing text transport plus local response validation.
- The provider response text, parsed valid judgment, token counts, finish reason and request ID are saved. Invalid responses are saved as invalid, never silently repaired. Error bodies are retained with configured API key values redacted.
- On HTTP 4xx errors (including 429), execution stops for review; Retry-After is retained. Timeout/unknown outcomes also stop the run and remain uncertain rather than becoming refusals.
- A fingerprint includes dataset/prompt identity, configured model ID, exact input, shared response format, temperature and output token limit. A previous recorded attempt with that fingerprint is reused, including invalid, failed and uncertain attempts. There is no hidden resend. The explicit Retry unfinished action creates a new linked run after review and cost acknowledgement; details are below.
- Stop after current request does not discard an already-running call. Its response is saved, and the remaining items are cancelled.
- Progress, saved run history, paginated response review and full JSON download read PostgreSQL. Browser refresh does not delete the run.
- Restart marks unfinished runs interrupted and in-flight attempts uncertain; they are never resent automatically. A PostgreSQL advisory lock prevents recovery from interfering with another live worker.

## Selecting and inspecting saved results

The Overview and model-results Run selectors accept any finished run from the
same dataset. The default is the latest finished run per model. Once selected,
the saved run ID follows filters, search, pagination, model links, and prompt
navigation. Partial runs are not backfilled from older runs. Active runs must be
reviewed in Runs until finished. Foreign dataset/run combinations return 404.
The numerical export dialog defaults to the selected run.

Prompt details display the selected model/run's saved status, classification,
Match/Mismatch against gold.label, explanation, safety rating, probability, error
and original response. A model selector lets a prepared prompt open a model's
saved results. Missing/invalid responses are not presented as valid classifications.

## Explicit retries

Saved runs offer **Retry unfinished**. A read-only plan shows valid responses to
preserve, requests to resend, and uncertain attempts. A second acknowledgement is
required when uncertain requests may already have been processed and charged.
Only the explicit acknowledged POST schedules work; planning, browsing and reloads
do not send requests. The worker still has no automatic retries.

A retry creates a new run containing the source run's full panel. Valid responses
are linked directly and marked reused. Failed, invalid, uncertain and cancelled
items receive new attempt fingerprints, retaining the original immutable prompts,
model IDs and settings. The source run and its failed responses remain unchanged.
The new settings record retryOf, retryRequests and acknowledgement of uncertainty.
Submission tokens are idempotent: a repeated submission does not create another
run or paid attempt. Current provider credentials are required only for items
being retried. Changed model IDs, obsolete prompt contracts or bad checksums
block retries; start a separate new run in those cases.

Retry fingerprints identify explicit attempts. Starting an ordinary run with the
original settings still uses its original fingerprint/reuse registry; it does not
silently substitute a later retry. Select the linked retry run to inspect its
combined preserved and retried results. Provider charges are not refunded when a
response is invalid or uncertain. No hard dollar spend cap is implemented.

## Deleting saved runs

The compact saved-run row offers **Delete** with a confirmation explaining the
permanent removal. Deletion removes that run, its items and only responses with
no remaining run references. Dataset prompts and responses used by other runs
are preserved. Deleting unshared responses clears their reuse history, so later
evaluations can incur new charges. Export important results first.

Deletion is disabled while any run is active. A transaction-level database lock
serializes run creation/retry creation/deletion so an executing worker cannot
lose a saved response between lookup and attachment. CSRF protection and dataset
ownership checks apply to retry and delete endpoints.

Classification metrics use gold.label; PCC uses gold.rating_mean; BCE uses v2
positive_probability. Missing inputs remain unavailable. Historical standalone
terminal smoke-test files are not automatically imported as runs.

## Verification

Unit/MVC tests use fake providers and intercepted HTTP. Database integration tests (`RunPersistenceTest` and `FoundationTest`) require the dedicated `ligtask_test` database. They never use the production database; each test first checks the `_test` suffix before clearing test tables. The test worker is disabled and fake providers are driven explicitly, so no API calls are made by the suite.

Run full checks from the project directory:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home scripts/mvn -o verify -q
```

The V3 migration adds `evaluation_run`, `evaluation_run_item` and `evaluation_response`. Existing datasets and source gold metadata are not rewritten. Existing snapshots from older imports use the current output contract at preparation time without modifying the source.
