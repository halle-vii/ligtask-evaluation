# Milestone verification — 14 October 2026

Environment: macOS, Oracle JDK 22.0.1 targeting Java 21, Maven 3.9.12, Spring Boot 3.5.16, PostgreSQL 16.11. Used a project-local PostgreSQL cluster on 127.0.0.1:55432. Docker startup and Windows instructions were not executed.

## Final build and integration checks

Command: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home scripts/mvn verify -q`

Result: **7 tests, 0 failures, 0 errors, 0 skipped** against the dedicated `ligtask_test` PostgreSQL database. Final test execution: 2.096 seconds. Flyway successfully applied V1 to an initially empty database and validated it on subsequent application starts.

Checks:

- Full synthetic grid: 24 instances, 2 bases, 4 base/context pairs, 2 records per expected factor cell, 0 missing variants.
- Partial fixture: 3 records, 1 base, 9 missing reference variants, 1 missing context; accepted with warnings.
- Missing required fields, duplicate instance IDs, invalid task/language/context values, conflicting context relationships, malformed JSON, duplicate JSON keys, and trailing content rejected.
- Preview renders with no database insert. Confirmation imports 24 records. Duplicate source/name/version and repeat confirmation do not create more data.
- Invalid previews do not retain an importable pending document. POST without CSRF is rejected. Unknown dataset IDs return 404.
- A late duplicate instance causes PostgreSQL uniqueness failure; the transaction rolls back both the dataset and every prompt row.
- Unknown source context stays UNKNOWN/null despite SAFE in an identifier. Arbitrary source metadata and translation metadata survive storage. There is no inferred human ground truth column.

## Actual process restart / HTTP check

Command: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home python3 scripts/verify-persistence.py`

The script uses cookies and CSRF to upload the real multipart file, preview, confirm, read the saved page, terminate Java, and launch a new Java process against the same PostgreSQL database. The final build was checked again using the existing synthetic dataset.

Result: **PASS — 24 records, Filipino UTF-8 text retained, same dataset identity, identical rendered dataset page before and after restart.** Synthetic dataset ID: `1ac8c0bc-75bd-4dbc-87f2-d568bcbaff54`.

Database restart and backup/restore were documented but not executed. Persistence verification specifically covers a full application-process restart against persistent PostgreSQL storage.

## Browser inspection

Opened http://127.0.0.1:8080 in a new Chrome tab and inspected accessibility state and a rendered screenshot. Verified the gradient LIGTASK wordmark, upload area, provisional/synthetic notice, download links, native file picker, preview control, and stored dataset list (24 records). Opened the native file chooser. The complete preview/confirm workflow was verified through MockMvc and real HTTP; it was not completed manually in the native browser. Drag/drop and mobile layouts were implemented but not manually exercised.

Figma inspection was restricted to Evaluation 60:31 and its Dashboard 60:35 / Specific LLM 60:416 children. See design.md for deliberate adaptations for this milestone.

## Pending external inputs

No real dataset was supplied. All imported examples are clearly marked synthetic. Real-export adapter mapping, identifiers, context/policy availability, translation selection/review rules, and official completeness expectations require the actual JSON. Human annotation remains external; future imports need separately versioned votes/reference labels and provenance. Live LLM calls, model outputs, metrics, and statistical analysis remain outside this milestone.

## Evaluation screens follow-up — 14 October 2026

Final command: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home scripts/mvn verify -q`

Result: **12 tests, 0 failures, 0 errors, 0 skipped** against PostgreSQL, including the original 7 tests. New coverage verifies overview/model/prompt rendering, task/language/context filters, Filipino full-text search, pagination, preserved selection on return, cross-dataset prompt rejection, unavailable metrics without mock values, invalid filter/model/page handling, empty states, all-matching-record CSV export, UTF-8, formula protection, HTML escaping, and unknown context.

Browser verification: opened `/evaluation`, inspected the overview screenshot, clicked Claude-3.5-sonnet, selected Filipino + SAFE + NLU, applied filters, and observed **2 matching prompts out of 24**. Inspected the model-view screenshot including the locally served Figma filter asset. Opened SYN_001_SAFE_NLU_FIL and inspected its full query, source context, policy, category-not-supplied state, and prompt navigation. Labels/results remain pending. The updated application was restarted on port 8080 against the existing database, retaining the synthetic dataset. Mobile CSS and dataset-switch interaction were not manually exercised.

The initial report above describes the foundation milestone. The evaluation screens are now implemented as read-only views; annotation/result imports and metric calculations remain pending.

## Persistent navigation follow-up — 14 October 2026

Final build: **14 PostgreSQL tests passed**, 0 failures/errors/skips. Added checks for the shared sidebar on overview, prompts, model results, prompt details, and coverage; canonical coverage redirects and missing-dataset handling; preserved filters/model links; dataset-selector routes that retain the current section and reset pagination for the newly selected dataset. All original import and evaluation-view tests remain passing.

Chrome checks: collapsed the overview sidebar into an icon rail, navigated to Prompts and then Dataset coverage, and confirmed the collapsed state persisted. Expanded it on coverage and visually checked the active section, common layout, full dataset coverage counts, and absence of back-button navigation. Sidebar controls expose accessible expand/collapse labels; nav icons retain labels/tooltips. No schema or stored data changed.
