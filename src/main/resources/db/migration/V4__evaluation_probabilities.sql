ALTER TABLE evaluation_response ADD COLUMN positive_probability double precision
 CHECK (positive_probability >= 0 AND positive_probability <= 1);
-- Legacy judgments remain NULL; never derive a probability from their ratings.
