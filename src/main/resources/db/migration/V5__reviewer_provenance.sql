-- Earlier actor strings cannot establish whether or how a reviewer authenticated.
ALTER TABLE review_event ADD COLUMN actor_source VARCHAR(16) NOT NULL DEFAULT 'LEGACY_UNKNOWN';
ALTER TABLE review_event ADD COLUMN actor_issuer VARCHAR(2048);
ALTER TABLE review_event ADD CONSTRAINT review_event_actor_provenance CHECK (
    (actor_source IN ('LEGACY_UNKNOWN', 'DEMO_LABEL') AND actor_issuer IS NULL)
    OR (actor_source = 'VERIFIED_JWT' AND actor_issuer IS NOT NULL AND LENGTH(TRIM(actor_issuer)) > 0)
);
