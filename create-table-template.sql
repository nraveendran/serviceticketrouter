CREATE EXTENSION vector;


DROP TABLE IF EXISTS raw_311_service_requests;
DROP TABLE IF EXISTS service_requests;
DROP TABLE IF EXISTS synthetic_routing_examples;

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




CREATE INDEX idx_sre_service_type
ON synthetic_routing_examples(service_request_type);

CREATE INDEX idx_sre_department
ON synthetic_routing_examples(department);

CREATE INDEX idx_sre_difficulty
ON synthetic_routing_examples(difficulty);



CREATE TABLE service_type_metadata (
    id BIGSERIAL PRIMARY KEY,
    service_request_type TEXT NOT NULL,
    department TEXT,
    priority TEXT,
    metadata_json JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT now(),
    reviewed BOOLEAN DEFAULT false
);

CREATE TABLE synthetic_service_request_descriptions (
    service_request_number TEXT PRIMARY KEY,
	 service_request_type TEXT NOT NULL,
    department TEXT NOT NULL,
    priority TEXT NOT NULL,
	 generated_description TEXT NOT NULL,
    difficulty TEXT,
 created_at TIMESTAMP DEFAULT now()
);

ALTER TABLE synthetic_service_request_descriptions
ADD COLUMN embedding vector(1536);