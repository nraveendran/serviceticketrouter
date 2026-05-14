CREATE EXTENSION vector;


DROP TABLE IF EXISTS raw_311_service_requests;
DROP TABLE IF EXISTS service_requests;

CREATE TABLE raw_311_service_requests (
    service_request_number TEXT,
    address TEXT,
    city_council_district TEXT,
    department TEXT,
    service_request_type TEXT,
    ert_estimated_response_time TEXT,
    overall_service_request_due_date TEXT,
    status TEXT,
    created_date TEXT,
    update_date TEXT,
    closed_date TEXT,
    outcome TEXT,
    priority TEXT,
    method_received_description TEXT,
    unique_key TEXT,
    lat_location TEXT
);

CREATE TABLE service_requests (
    id BIGSERIAL PRIMARY KEY,

    unique_key TEXT,
    service_request_number TEXT,

    address TEXT,
    city_council_district INTEGER,

    department TEXT,
    service_request_type TEXT,

    ert_estimated_response_time TEXT,

    status TEXT,
    outcome TEXT,
    priority TEXT,
    method_received_description TEXT,

    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    closed_at TIMESTAMP,
    due_at TIMESTAMP,

    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,

    resolution_hours DOUBLE PRECISION
);

CREATE TABLE service_requests (
    id BIGSERIAL PRIMARY KEY,

    unique_key TEXT,
    service_request_number TEXT,

    address TEXT,
    city_council_district TEXT,

    department TEXT,
    service_request_type TEXT,

    ert_estimated_response_time TEXT,

    status TEXT,
    outcome TEXT,
    priority TEXT,
    method_received_description TEXT,

    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    closed_at TIMESTAMP,
    due_at TIMESTAMP,

    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,

    resolution_hours DOUBLE PRECISION
);


CREATE TABLE synthetic_routing_examples (
    synthetic_id TEXT PRIMARY KEY,
    source_row_number INTEGER,
    citizen_description TEXT,
    service_request_type TEXT,
    department TEXT,
    priority TEXT,
    request_count INTEGER,
    allocation_count_for_source_row INTEGER,
    difficulty TEXT
);

CREATE INDEX idx_sre_service_type
ON synthetic_routing_examples(service_request_type);

CREATE INDEX idx_sre_department
ON synthetic_routing_examples(department);

CREATE INDEX idx_sre_difficulty
ON synthetic_routing_examples(difficulty);

ALTER TABLE synthetic_routing_examples
ADD COLUMN embedding vector(1536);