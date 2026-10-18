# LIGTask evaluation foundation

Local Java / Spring Boot / PostgreSQL / Thymeleaf application. Milestone 1 imports prepared datasets; human annotation stays external. No live LLM calls, output coding, metrics, or statistical analysis are implemented.

## View the evaluation screens

Open **http://127.0.0.1:8080/evaluation**. This opens the most recently imported dataset, or an upload-first state if no dataset exists. A dataset selector lets you switch versions. Use **Evaluation** in the header to open the workspace. Imported dataset links open Dataset coverage within the same evaluation workspace.

- **Overview:** seven model entries from the Evaluation design, Overview/NLU/NLR/NLG tabs, and unavailable metric values. Click a model name or **View**.
- **Model results:** six metric cards, language/context/task filters with All/reset options, search, 12 prompts per page, and **Export CSV**. Apply filters to refresh the records and matching count. Search covers full instance/upstream/base/context identifiers, prepared query, context text, and policy text.
- **Prompt detail:** click an instance or query to inspect the full text, source context condition, policy, supplied category/context attributes, translation metadata, and unavailable result/provenance fields. Previous/next navigation stays within the matching set. The shared sidebar lists all seven models with local brand logos, preserving filters and the originating table page.
- **Runs:** dataset/model setup with planned request counts and a stored-input preview, plus Progress and Saved runs tabs. Start run remains disabled until backend execution is integrated. See [the LLM handoff](docs/llm-handoff.md) for the one-response policy and integration requirements.
- **Prompts:** the same prepared-record browser without a model selection.

The sidebar is present on Overview, Runs, Prompts, model views, prompt details, and Dataset coverage. Use its bottom-right control to collapse it into an icon rail or expand the labels; this browser remembers the preference between pages. Navigation icons retain accessible names and tooltips. It stays available while scrolling, with the current section highlighted. Coverage is `/evaluation/{datasetId}/coverage`; old `/datasets/{datasetId}` links redirect there. Coverage counts cover the whole dataset, while sidebar links preserve prompt filters. Changing datasets stays in the current section (prompt details switch to that dataset's prompt/model list).

Model names are design-reference entries, not provider registrations. No annotations, results, or metrics are fabricated. CSV exports all matching prompts across pages with dataset/version/synthetic provenance, blank human/model label cells, and `NOT_EVALUATED`. `model_view` describes the selected screen only. UTF-8 CSV uses a BOM and standard quoted cells; spreadsheet-formula prefixes receive a leading apostrophe for safe viewing. This display export is not the canonical exact-text import format; PostgreSQL retains the original text.

The three evaluation layouts follow Figma's Evaluation section. Missing category or structured context attributes remain **Not supplied**. The optional provisional display keys `category` (string) and `context_attributes` (object) are read only when explicitly supplied; other JSON remains inspectable in the source record.

## Start locally

Requirements: JDK **21–25**, Maven **3.9+**, PostgreSQL **16+** (or Docker Desktop). Java 8 will not work. Set `JAVA_HOME` to your JDK directory. On this macOS machine, use `export JAVA_HOME=$(/usr/libexec/java_home -v 22)`; the default `java` is Java 8, so the commands below use the selected JDK explicitly.

```sh
# Start PostgreSQL with durable named-volume storage:
export DB_PASSWORD=local-ligtask
docker compose up -d --wait db
# Build and run (scripts/mvn uses installed Maven or the local downloaded copy):
./scripts/mvn package -DskipTests
"$JAVA_HOME/bin/java" -jar target/ligtask-evaluation-0.1.0.jar
```

Open **http://127.0.0.1:8080**. Browse or drop the prepared dataset from Finder or your file manager, choose **Submit**, inspect errors/warnings/coverage/records, then **Confirm import**. Invalid uploads show field errors in a modal on the upload page, retaining the selected file; close it with Got it, ×, or Escape. Valid uploads open the preview, with Confirm import beside its heading and a back-to-top button when scrolled. Dataset coverage uses a compact row for completeness status, exact counts and dataset details. Preview alone writes no dataset. The dataset list and record pages read from PostgreSQL. Imports are immutable versions; changes require a new version, never an overwrite. Reuploading the same file or name/version is rejected. Preview expires after 30 minutes of session inactivity and is lost when the application restarts.

With an existing PostgreSQL installation, create an empty database owned by a dedicated role and configure:

```sh
export DB_URL=jdbc:postgresql://127.0.0.1:5432/ligtask
export DB_USER=ligtask
export DB_PASSWORD='your-local-password'
```

Flyway applies migrations automatically at startup. No manual table creation is required. `PORT` overrides 8080. The HTTP server binds only to 127.0.0.1. This milestone is a single-user local tool without account login; forms use CSRF protection. Do not expose it publicly.

### This workspace's verified setup (macOS)

The machine already had JDK 22 and Homebrew PostgreSQL 16. Docker was not running. Verification used an isolated cluster under `.local/postgres`, loopback port 55432, and databases `ligtask` and `ligtask_test`. That cluster uses local trust authentication and is bound to loopback for development only.

```sh
# First initialization only, if the directory does not already exist:
/opt/homebrew/opt/postgresql@16/bin/initdb -D .local/postgres -A trust -U ligtask --encoding=UTF8 --locale=C
/opt/homebrew/opt/postgresql@16/bin/pg_ctl -D .local/postgres -l .local/postgres.log -o '-h 127.0.0.1 -p 55432 -k /tmp' start
# First database creation only:
/opt/homebrew/opt/postgresql@16/bin/createdb -h 127.0.0.1 -p 55432 -U ligtask ligtask
# No DB_PASSWORD is needed for this project-local cluster:
unset DB_PASSWORD
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home
./scripts/mvn package -DskipTests
"$JAVA_HOME/bin/java" -jar target/ligtask-evaluation-0.1.0.jar
```

Use **one** database startup option; Docker and this cluster share port 55432. On a fresh checkout, install Maven or download Apache Maven 3.9+ into `.tools/`; dependency downloads need network access. There is no checked-in binary dependency cache.

## Tests and persistence verification

Tests use real PostgreSQL, never H2. Create a dedicated database ending `_test`; test setup truncates only that database's dataset tables after verifying its name.

```sh
# Project-local PostgreSQL:
/opt/homebrew/opt/postgresql@16/bin/createdb -h 127.0.0.1 -p 55432 -U ligtask ligtask_test
# Docker alternative:
# docker compose exec db createdb -U ligtask ligtask_test
./scripts/mvn test
# Override when needed:
# export TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/ligtask_test
```

The integration suite covers field/enumeration/duplicate validation, malformed JSON, full and partial coverage, preview without persistence, confirmed import, duplicate/replayed submissions, CSRF, missing dataset 404, raw metadata/UTF-8 preservation, unknown context, and database rollback after a late uniqueness failure.

After packaging, `python3 scripts/verify-persistence.py` performs a real HTTP import on port 18080, stops the Java process, starts a second Java process against the same configured database, and checks the stored dataset and Filipino text. It imports the synthetic TFG sample into the configured database or reuses an exact matching import. Older provisional demos already in PostgreSQL remain browsable. Run it with the same database environment as the app. See [verification evidence](docs/verification.md).

Manual check: import a sample, note the dataset URL, stop Java with Ctrl-C, start it again, open the same URL, and verify records/coverage. Stop/start PostgreSQL as well to check durable storage.

## Stop, backup, restore

Stop Java with Ctrl-C. `docker compose stop db` preserves the named volume; `docker compose down` also preserves it. **Do not use `down -v`** unless you intend to erase the data. For the project-local cluster:

```sh
/opt/homebrew/opt/postgresql@16/bin/pg_ctl -D .local/postgres stop -m fast
```

Back up before schema changes; substitute your actual host, port, and role. PostgreSQL tools read `PGPASSWORD` if needed.

```sh
pg_dump -h 127.0.0.1 -p 55432 -U ligtask -Fc ligtask -f ligtask-backup.dump
# Restore into a NEW empty database, with Java stopped:
createdb -h 127.0.0.1 -p 55432 -U ligtask ligtask_restored
pg_restore -h 127.0.0.1 -p 55432 -U ligtask -d ligtask_restored ligtask-backup.dump
# Then set DB_URL to the restored database before starting Java.
```

## Provisional contract and architecture

See [the upload contract](docs/tfg-import.md), [legacy fixture contract](docs/import-contract.md) and [design notes](docs/design.md). `DatasetImporter` enforces the bundled TFG structure before `TfgJsonAdapter` maps accepted source arrays; `DatasetStore` owns transactional persistence; MVC/Thymeleaf handles upload/preview/confirmation. The TFG adapter expands each source entry into three task instances and records the origin of generated identifiers. See [the real TFG mapping](docs/tfg-import.md). Accepted source fields are preserved as JSONB. Extra fields are rejected by the upload schema. Migrations provide unique constraints and dataset foreign keys.

The supplied real export is supported: 400 source entries become 1,200 prompts for 100 base queries. Upload the JSON from any folder through the preview/confirm flow; it does not need to be in the project. Queries, task instructions, expected output schemas, contexts, and source metadata are preserved separately. Only the main dataset structure is accepted, including nested fields/types and known optional variations. Missing, extra or wrongly typed fields show errors and block import. Download the JSON schema from the upload screen. A valid synthetic test file remains at `src/main/resources/static/samples/synthetic-tfg.json`, and `samples/invalid-structure-tfg.json` intentionally fails structure validation. Old provisional samples are rejected. The included source gold labels are unconfirmed contextual labels, not human ground truth.

Pending research inputs: independent human annotations, policy text, translation approval provenance, execution protocol and final scoring choices. Future annotation imports should reference `prompt_instance.id`, retain each independent vote and provenance, and version finalized labels separately. The current schema deliberately has no human-label column derived from an identifier.

Verify this export after packaging with `python3 scripts/verify-persistence.py --file /path/to/LIGTask_TFG_dataset_en_fil_final.json`.

## Windows (PowerShell)

Install JDK 21–25, Maven 3.9+, and Docker Desktop or PostgreSQL. Set `JAVA_HOME` to the actual JDK directory and ensure `mvn` is on PATH. From the project directory:

```powershell
$env:DB_PASSWORD = "local-ligtask"
docker compose up -d --wait db
mvn package -DskipTests
& "$env:JAVA_HOME\bin\java.exe" -jar target/ligtask-evaluation-0.1.0.jar
```

Use http://127.0.0.1:8080. Windows instructions are provided but were not executed on this macOS machine.

Technology references: [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Maven 3.9.12 release](https://maven.apache.org/docs/3.9.12/release-notes.html).
