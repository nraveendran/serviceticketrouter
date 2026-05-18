package com.serviceticketrouter.queue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.logging.Logger;

@Service
public class System311QueueService {
    private static final Logger LOGGER = Logger.getLogger(System311QueueService.class.getName());
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "Received",
            "NeedsReview",
            "ReadyForDispatch",
            "NeedsDispatch",
            "Dispatched",
            "DispatchFailed",
            "Closed"
    );

    private final String databaseUrl;
    private final String databaseUsername;
    private final String databasePassword;

    public System311QueueService(
            @Value("${database.url}") String databaseUrl,
            @Value("${database.username}") String databaseUsername,
            @Value("${database.password:}") String databasePassword
    ) {
        this.databaseUrl = databaseUrl;
        this.databaseUsername = databaseUsername;
        this.databasePassword = databasePassword;
    }

    public System311QueueResponse create(System311QueueCreateRequest request) {
        validate(request);

        String sql = """
                INSERT INTO system_311_queue (
                    description,
                    address,
                    predicted_department,
                    predicted_service_request_type,
                    predicted_priority,
                    routing_confidence,
                    status,
                    status_reason,
                    department_ticket_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING
                    queue_id,
                    description,
                    address,
                    predicted_department,
                    predicted_service_request_type,
                    predicted_priority,
                    routing_confidence,
                    status,
                    status_reason,
                    service_request_number,
                    department_ticket_id,
                    created_by,
                    created_at,
                    updated_at
                """;

        try (Connection connection = DriverManager.getConnection(databaseUrl, databaseUsername, databasePassword);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, request.description().trim());
            statement.setString(2, blankToNull(request.address()));
            statement.setString(3, blankToNull(request.predictedDepartment()));
            statement.setString(4, blankToNull(request.predictedServiceRequestType()));
            statement.setString(5, blankToNull(request.predictedPriority()));
            if (request.routingConfidence() == null) {
                statement.setNull(6, java.sql.Types.DOUBLE);
            } else {
                statement.setDouble(6, request.routingConfidence());
            }
            statement.setString(7, request.status());
            statement.setString(8, blankToNull(request.statusReason()));
            statement.setString(9, blankToNull(request.departmentTicketId()));

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Queue insert did not return a row.");
                }

                System311QueueResponse response = toResponse(resultSet);
                LOGGER.info(() -> "Created system_311_queue row. queueId=" + response.queueId()
                        + ", status=" + response.status());
                return response;
            }
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create queue item: " + e.getMessage(), e);
        }
    }

    private void validate(System311QueueCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (request.description() == null || request.description().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description is required");
        }
        if (request.status() == null || request.status().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        if (!ALLOWED_STATUSES.contains(request.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + request.status());
        }
    }

    private System311QueueResponse toResponse(ResultSet resultSet) throws SQLException {
        return new System311QueueResponse(
                resultSet.getLong("queue_id"),
                resultSet.getString("description"),
                resultSet.getString("address"),
                resultSet.getString("predicted_department"),
                resultSet.getString("predicted_service_request_type"),
                resultSet.getString("predicted_priority"),
                nullableDouble(resultSet, "routing_confidence"),
                resultSet.getString("status"),
                resultSet.getString("status_reason"),
                resultSet.getString("service_request_number"),
                resultSet.getString("department_ticket_id"),
                resultSet.getString("created_by"),
                toLocalDateTime(resultSet.getTimestamp("created_at")),
                toLocalDateTime(resultSet.getTimestamp("updated_at"))
        );
    }

    private Double nullableDouble(ResultSet resultSet, String columnName) throws SQLException {
        double value = resultSet.getDouble(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
