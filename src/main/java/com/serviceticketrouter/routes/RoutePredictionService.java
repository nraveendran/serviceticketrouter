package com.serviceticketrouter.routes;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.serviceticketrouter.embeddings.OpenAiEmbeddingClient;
import com.serviceticketrouter.embeddings.PgVector;

@Service
public class RoutePredictionService {
    private static final Logger LOGGER = Logger.getLogger(RoutePredictionService.class.getName());
    private static final int NEAREST_LIMIT = 25;

    private final OpenAiEmbeddingClient embeddingClient;
    private final String databaseUrl;
    private final String databaseUsername;
    private final String databasePassword;

    public RoutePredictionService(
            OpenAiEmbeddingClient embeddingClient,
            @Value("${database.url}") String databaseUrl,
            @Value("${database.username}") String databaseUsername,
            @Value("${database.password:}") String databasePassword
    ) {
        this.embeddingClient = embeddingClient;
        this.databaseUrl = databaseUrl;
        this.databaseUsername = databaseUsername;
        this.databasePassword = databasePassword;
    }

    public RoutePredictionResponse predict(String description) {
        String requestSummary = summarizeDescription(description);
        LOGGER.info(() -> "Route prediction started. description=" + requestSummary);

        try {
            LOGGER.info("Generating embedding for route prediction request.");
            List<BigDecimal> embedding = embeddingClient.createEmbedding(description);
            LOGGER.info(() -> "Generated route prediction embedding. dimensions=" + embedding.size()
                    + ", description=" + requestSummary);
            String embeddingLiteral = PgVector.toLiteral(embedding);

            LOGGER.info(() -> "Querying nearest synthetic routing examples. nearestLimit=" + NEAREST_LIMIT
                    + ", description=" + requestSummary);
            RoutePredictionResponse response = findBestRoute(embeddingLiteral);
            LOGGER.info(() -> "Route prediction completed. description=" + requestSummary
                    + ", department=" + response.department()
                    + ", serviceRequestType=" + response.serviceRequestType()
                    + ", priority=" + response.priority()
                    + ", confidence=" + response.confidence());
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning(() -> "Route prediction interrupted. description=" + requestSummary);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Prediction was interrupted.", e);
        } catch (ResponseStatusException e) {
            LOGGER.warning(() -> "Route prediction failed with status=" + e.getStatusCode()
                    + ". description=" + requestSummary
                    + ", reason=" + e.getReason());
            throw e;
        } catch (Exception e) {
            LOGGER.warning(() -> "Route prediction failed. description=" + requestSummary
                    + ", error=" + e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Prediction failed: " + e.getMessage(), e);
        }
    }

    private RoutePredictionResponse findBestRoute(String embeddingLiteral) throws SQLException {
        String sql = """
                WITH nearest AS (
                    SELECT
                        service_request_type,
                        department,
                        priority,
                        1 - (embedding <=> CAST(? AS vector)) AS similarity
                    FROM synthetic_service_request_descriptions
                    WHERE embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                )
                SELECT
                    service_request_type,
                    department,
                    priority,
                    SUM(similarity) AS score,
                    AVG(similarity) AS confidence
                FROM nearest
                GROUP BY service_request_type, department, priority
                ORDER BY score DESC
                LIMIT 1
                """;

        try (Connection connection = DriverManager.getConnection(databaseUrl, databaseUsername, databasePassword);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            
            statement.setString(1, embeddingLiteral);
            statement.setString(2, embeddingLiteral);
            statement.setInt(3, NEAREST_LIMIT);

            try (ResultSet resultSet = statement.executeQuery()) {
                LOGGER.info("Nearest route aggregation query executed.");
                if (!resultSet.next()) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "No synthetic service request descriptions with embeddings were found."
                    );
                }

                String department = resultSet.getString("department");
                String serviceRequestType = resultSet.getString("service_request_type");
                String priority = resultSet.getString("priority");
                double score = resultSet.getDouble("score");
                double confidence = clampConfidence(resultSet.getDouble("confidence"));

                LOGGER.info(() -> "Nearest route aggregation selected. department=" + department
                        + ", serviceRequestType=" + serviceRequestType
                        + ", priority=" + priority
                        + ", score=" + score
                        + ", confidence=" + confidence);

                return new RoutePredictionResponse(
                        department,
                        serviceRequestType,
                        priority,
                        confidence
                );
            }
        }
    }

    private double clampConfidence(double confidence) {
        if (Double.isNaN(confidence)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private String summarizeDescription(String description) {
        String singleLine = description.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= 120) {
            return "\"" + singleLine + "\"";
        }
        return "\"" + singleLine.substring(0, 117) + "...\"";
    }
}
