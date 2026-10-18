# LLM execution handoff

## Current UI

Open Evaluation → Runs, or `/evaluation/{datasetId}/runs`. The shared sidebar and dataset selector retain the Runs section when changing datasets.

- Run setup shows the actual dataset prompt count, seven research-model choices, and a live planned-request count. Select all / clear selection is available.
- The input preview shows the first six stored task variants, using an explicit field whitelist: supplied task instruction, query, language, context, optional policy, and expected output format. Source `gold`, human labels, raw metadata and reference ratings are excluded. This display is not a frozen provider request template; approve the exact wrapper and language handling before execution.
- Progress and Saved runs tabs expose truthful empty states. No runs, responses, failures, timestamps or metrics are fabricated. Model connections are all shown as Not configured.
- Start run is disabled regardless of selection. There is no execution endpoint, background job, provider SDK, API-key storage, persisted run draft/history, or results retrieval integration yet. Reloading setup resets model choices. Tabs remain available at `#setup`, `#progress`, and `#history`.
- Existing Overview and model-results views have a disabled Run selector and a Manage runs link. These must be connected to real saved run data when implemented. Prompt details already have pending run/response fields.

## Confirmed user execution policy

The application will invoke selected LLMs with zero-shot inputs and retain their outputs. Plan for one response per dataset prompt per selected model to conserve credits. With 1,200 prompts and seven models, the UI estimate is 8,400 requests assuming one prompt per request; it is a count, not a monetary quote.

Before enabling Start run:

1. Implement durable run, work-item, response and attempt records. Freeze dataset identity, provider/model identifier, prompt-template version, model settings and one-response policy. Define canonical execution identity so rerunning with cosmetically changed settings or reuploaded equivalent data cannot silently spend credits again.
2. Enforce duplicate prevention in the backend and database; disabling a button is insufficient. Claim work atomically and save an outbound attempt before dispatch. Refreshing, viewing, exporting or scoring results must never dispatch requests.
3. Persist the exact sent input and raw response, identifiers and completion state before considering an item finished. Saved responses must be reusable independently of provider access and human annotation availability.
4. Resume only known unsent items. Disable automatic retries in both the job layer and provider SDKs. Timeouts/disconnects after dispatch are uncertain, not safe-to-resend failures. Reconcile using provider request IDs or supported idempotency where available. Exactly-once billing cannot be guaranteed across arbitrary network failures without provider support.
5. Make any potentially billable retry an explicit, separately approved action with affected item counts. Completed responses must never be silently overwritten or requested again.
6. Configure credentials on the backend (environment or an appropriate secret mechanism); expose only connection status in the UI. Add real readiness checks instead of enabling execution merely because a key exists.
7. Wire progress to stored states and link Saved runs / result selectors to actual responses. Distinguish completed, unsent, known failed and uncertain requests; show raw response separately from parsed/coded task labels and human reference labels.

Keep the named research models aligned with the manuscript. Confirm endpoint availability and hosting with the research team; do not substitute models automatically. No provider availability or pricing claims are made by the current static model list.

Human annotation remains external. The source export's `gold` is unconfirmed contextual metadata and must not be sent as an answer or treated as independent human ground truth. Model response coding and final statistical procedures still need their agreed protocol before metrics are enabled.
