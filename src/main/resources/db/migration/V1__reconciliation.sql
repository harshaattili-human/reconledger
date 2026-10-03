CREATE TABLE recon_batch (
    id VARCHAR(36) PRIMARY KEY,
    idempotency_key VARCHAR(80) NOT NULL UNIQUE,
    fingerprint CHAR(64) NOT NULL,
    business_date DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE source_record (
    batch_id VARCHAR(36) NOT NULL REFERENCES recon_batch(id),
    source_side VARCHAR(5) NOT NULL CHECK (source_side IN ('LEFT', 'RIGHT')),
    record_id VARCHAR(80) NOT NULL,
    reference VARCHAR(80) NOT NULL,
    amount DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (batch_id, source_side, record_id)
);

CREATE TABLE recon_result (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL REFERENCES recon_batch(id),
    reference VARCHAR(80) NOT NULL,
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN
        ('MATCHED', 'AMOUNT_MISMATCH', 'MISSING_LEFT', 'MISSING_RIGHT', 'DUPLICATE_REFERENCE')),
    review_state VARCHAR(16) NOT NULL CHECK (review_state IN ('NOT_REQUIRED', 'OPEN', 'IN_REVIEW', 'RESOLVED')),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    resolution_note VARCHAR(500),
    UNIQUE (batch_id, reference),
    CHECK ((outcome = 'MATCHED' AND review_state = 'NOT_REQUIRED') OR
           (outcome <> 'MATCHED' AND review_state <> 'NOT_REQUIRED')),
    CHECK ((review_state = 'RESOLVED' AND resolution_note IS NOT NULL) OR
           (review_state <> 'RESOLVED' AND resolution_note IS NULL))
);

CREATE TABLE review_event (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    result_id VARCHAR(36) NOT NULL REFERENCES recon_result(id),
    actor VARCHAR(80) NOT NULL,
    from_state VARCHAR(16) NOT NULL,
    to_state VARCHAR(16) NOT NULL,
    resulting_version INTEGER NOT NULL,
    note VARCHAR(500) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    UNIQUE (result_id, resulting_version)
);

CREATE INDEX review_event_result ON review_event(result_id, sequence);
