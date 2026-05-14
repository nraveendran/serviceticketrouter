COPY raw_311_service_requests
FROM '/Users/nidhishnair/workspace/serviceticketrouter/311_Service_Requests_October_1,_2020_to_Present_20260513.csv'
DELIMITER ','
CSV HEADER;


--sanitize raw data ADD
INSERT INTO service_requests (
    unique_key,
    service_request_number,
    address,
    city_council_district,
    department,
    service_request_type,
    ert_estimated_response_time,
    status,
    outcome,
    priority,
    method_received_description,
    created_at,
    updated_at,
    closed_at,
    due_at,
    latitude,
    longitude,
    resolution_hours
)
SELECT
    unique_key,
    service_request_number,
    address,

   NULLIF(city_council_district, ''),

    department,
    service_request_type,
    ert_estimated_response_time,

    status,
    outcome,
    priority,
    method_received_description,

    NULLIF(created_date, '')::TIMESTAMP,
    NULLIF(update_date, '')::TIMESTAMP,
    NULLIF(closed_date, '')::TIMESTAMP,
    NULLIF(overall_service_request_due_date, '')::TIMESTAMP,

    substring(lat_location FROM '\(([^,]+),')::DOUBLE PRECISION,
    substring(lat_location FROM ',([^)]+)\)')::DOUBLE PRECISION,

    EXTRACT(
        EPOCH FROM (
            NULLIF(closed_date, '')::TIMESTAMP
            -
            NULLIF(created_date, '')::TIMESTAMP
        )
    ) / 3600.0

FROM raw_311_service_requests;