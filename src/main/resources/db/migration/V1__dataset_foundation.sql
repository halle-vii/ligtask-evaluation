CREATE TABLE dataset_version (
 id uuid PRIMARY KEY, name text NOT NULL, version text NOT NULL, schema_version text NOT NULL,
 synthetic boolean NOT NULL, source_filename text NOT NULL, source_sha256 text NOT NULL UNIQUE,
 raw_document jsonb NOT NULL, imported_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(name, version)
);
CREATE TABLE prompt_instance (
 id uuid PRIMARY KEY, dataset_id uuid NOT NULL REFERENCES dataset_version(id),
 instance_id text NOT NULL, upstream_entry_id text NOT NULL, base_query_id text NOT NULL,
 context_id text NOT NULL, context_condition text NOT NULL CHECK(context_condition IN ('SAFE','UNSAFE','UNKNOWN')),
 task_type text NOT NULL CHECK(task_type IN ('NLU','NLR','NLG')),
 language_variant text NOT NULL CHECK(language_variant IN ('EN','FIL')),
 prepared_prompt_text text NOT NULL CHECK(length(trim(prepared_prompt_text))>0),
 context_text text, policy_text text, raw_record jsonb NOT NULL,
 UNIQUE(dataset_id,instance_id),
 UNIQUE(dataset_id,base_query_id,context_condition,task_type,language_variant)
);
CREATE INDEX prompt_dataset ON prompt_instance(dataset_id);
-- No ground truth column: future versioned annotation imports will reference prompt_instance.id.
