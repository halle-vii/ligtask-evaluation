-- Removing a dataset from the active list must retain identities and future result references.
ALTER TABLE dataset_version ADD COLUMN archived_at timestamptz;
CREATE INDEX dataset_active_imported ON dataset_version(imported_at DESC) WHERE archived_at IS NULL;
