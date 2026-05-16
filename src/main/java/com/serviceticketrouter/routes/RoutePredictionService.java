package com.serviceticketrouter.routes;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
    private static final int TOP_ROUTE_LIMIT = 3;

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
        RoutePredictionCandidate bestCandidate = predictCandidates(description, false).get(0);
        return new RoutePredictionResponse(
                bestCandidate.department(),
                bestCandidate.serviceRequestType(),
                bestCandidate.priority(),
                bestCandidate.confidence()
        );
    }

    public List<RoutePredictionCandidate> predictCandidates(String description, boolean evaluationFlag) {
        String requestSummary = summarizeDescription(description);
        LOGGER.info(() -> "Route prediction started. evaluationFlag=" + evaluationFlag
                + ", description=" + requestSummary);

        try {
            LOGGER.info("Generating embedding for route prediction request.");
            List<BigDecimal> embedding = embeddingClient.createEmbedding(description);
            LOGGER.info(() -> "Generated route prediction embedding. dimensions=" + embedding.size()
                    + ", description=" + requestSummary);
            String embeddingLiteral = PgVector.toLiteral(embedding);

            LOGGER.info(() -> "Querying nearest synthetic routing examples. nearestLimit=" + NEAREST_LIMIT
                    + ", evaluationFlag=" + evaluationFlag
                    + ", description=" + requestSummary);
            List<RoutePredictionCandidate> candidates = findBestRoutes(embeddingLiteral, evaluationFlag);
            RoutePredictionCandidate bestCandidate = candidates.get(0);
            LOGGER.info(() -> "Route prediction completed. description=" + requestSummary
                    + ", department=" + bestCandidate.department()
                    + ", serviceRequestType=" + bestCandidate.serviceRequestType()
                    + ", priority=" + bestCandidate.priority()
                    + ", confidence=" + bestCandidate.confidence());
            return candidates;
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

    private List<RoutePredictionCandidate> findBestRoutes(String embeddingLiteral, boolean evaluationFlag) throws SQLException {
        String sql = """
                WITH nearest AS (
                    SELECT
                        service_request_type,
                        department,
                        priority,
                        generated_description,
                        1 - (embedding <=> CAST(? AS vector)) AS similarity
                    FROM synthetic_service_request_descriptions
                    WHERE embedding IS NOT NULL
                      AND (? = false OR dataset_split = 'train')
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                ),
                grouped AS (
                    SELECT
                        service_request_type,
                        department,
                        priority,
                        COUNT(*) AS votes,
                        MAX(similarity) AS best_similarity,
                        AVG(similarity) AS avg_similarity,
                        SUM(similarity) AS total_similarity
                    FROM nearest
                    GROUP BY service_request_type, department, priority
                )
                SELECT
                    service_request_type,
                    department,
                    priority,
                    votes,
                    best_similarity,
                    avg_similarity,
                    (
                        best_similarity * 0.60 +
                        avg_similarity * 0.30 +
                        LEAST(votes, 5) * 0.02
                    ) AS score
                FROM grouped
                ORDER BY score DESC
                LIMIT ?
                """;

        try (Connection connection = DriverManager.getConnection(databaseUrl, databaseUsername, databasePassword);
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, embeddingLiteral);
            statement.setBoolean(2, evaluationFlag);
            statement.setString(3, embeddingLiteral);
            statement.setInt(4, NEAREST_LIMIT);
            statement.setInt(5, TOP_ROUTE_LIMIT);

            try (ResultSet resultSet = statement.executeQuery()) {
                LOGGER.info("Nearest route aggregation query executed.");
                List<RoutePredictionCandidate> candidates = new ArrayList<>();
                int rank = 0;

                while (resultSet.next()) {
                    rank++;
                    String department = resultSet.getString("department");
                    String serviceRequestType = resultSet.getString("service_request_type");
                    String priority = resultSet.getString("priority");
                    int votes = resultSet.getInt("votes");
                    double bestSimilarity = resultSet.getDouble("best_similarity");
                    double avgSimilarity = resultSet.getDouble("avg_similarity");
                    double score = resultSet.getDouble("score");
                    double confidence = clampConfidence(score);

                    RoutePredictionCandidate candidate = new RoutePredictionCandidate(
                            rank,
                            department,
                            serviceRequestType,
                            priority,
                            votes,
                            bestSimilarity,
                            avgSimilarity,
                            score,
                            confidence
                    );
                    candidates.add(candidate);

                    LOGGER.info(() -> "Nearest route candidate rank=" + candidate.rank()
                            + ", department=" + department
                            + ", serviceRequestType=" + serviceRequestType
                            + ", priority=" + priority
                            + ", votes=" + votes
                            + ", bestSimilarity=" + bestSimilarity
                            + ", avgSimilarity=" + avgSimilarity
                            + ", score=" + score
                            + ", confidence=" + confidence);
                }

                if (candidates.isEmpty()) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "No synthetic service request descriptions with embeddings were found."
                    );
                }

                return candidates;
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
