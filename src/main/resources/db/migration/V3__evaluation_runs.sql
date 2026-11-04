CREATE TABLE evaluation_run (
 id uuid PRIMARY KEY, dataset_id uuid NOT NULL REFERENCES dataset_version(id),
 client_token uuid NOT NULL UNIQUE, plan_hash text NOT NULL,
 status text NOT NULL CHECK(status IN ('QUEUED','RUNNING','CANCEL_REQUESTED','COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','INTERRUPTED')),
 settings jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 started_at timestamptz, finished_at timestamptz
);
CREATE UNIQUE INDEX evaluation_one_active_run ON evaluation_run ((true))
 WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED');
CREATE INDEX evaluation_run_dataset ON evaluation_run(dataset_id,created_at DESC);
CREATE TABLE evaluation_response (
 id uuid PRIMARY KEY, fingerprint text NOT NULL UNIQUE,
 dataset_id uuid NOT NULL REFERENCES dataset_version(id),
 prompt_id uuid NOT NULL REFERENCES prompt_instance(id), model_key text NOT NULL,
 model_id text NOT NULL, input_snapshot jsonb NOT NULL, settings jsonb NOT NULL,
 status text NOT NULL CHECK(status IN ('IN_FLIGHT','VALID','INVALID_RESPONSE','HTTP_ERROR','UNCERTAIN')),
 response jsonb, classification text, safety_rating integer CHECK(safety_rating BETWEEN 1 AND 10),
 explanation text, error_detail text, http_status integer, retry_after text,
 started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz
);
CREATE TABLE evaluation_run_item (
 id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES evaluation_run(id),
 prompt_id uuid NOT NULL REFERENCES prompt_instance(id), instance_id text NOT NULL,
 model_key text NOT NULL, model_id text NOT NULL, fingerprint text NOT NULL,
 input_snapshot jsonb NOT NULL, response_id uuid REFERENCES evaluation_response(id),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','IN_FLIGHT','VALID','INVALID_RESPONSE','HTTP_ERROR','UNCERTAIN','CANCELLED')),
 reused boolean NOT NULL DEFAULT false,
 UNIQUE(run_id,prompt_id,model_key)
);
CREATE INDEX evaluation_items_run ON evaluation_run_item(run_id,status);
