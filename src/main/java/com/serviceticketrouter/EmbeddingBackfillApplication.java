package com.serviceticketrouter;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EmbeddingBackfillApplication {
    private static final Logger LOGGER = Logger.getLogger(EmbeddingBackfillApplication.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOTAL_RECORDS_TO_PROCESS = 20000;

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        config.validate();

        EmbeddingClient embeddingClient = new EmbeddingClient(config);

        int totalUpdated = 0;
        LOGGER.info(() -> "Starting embedding backfill. batchSize=" + config.batchSize()
                + ", maxTotalRecordsToProcess=" + MAX_TOTAL_RECORDS_TO_PROCESS
                + ", model=" + config.openAiEmbeddingModel());

        while (totalUpdated < MAX_TOTAL_RECORDS_TO_PROCESS) {
            int remainingRecords = MAX_TOTAL_RECORDS_TO_PROCESS - totalUpdated;
            int fetchLimit = Math.min(config.batchSize(), remainingRecords);
            List<SyntheticDescription> descriptions = fetchRowsWithoutEmbeddings(config, fetchLimit);
            if (descriptions.isEmpty()) {
                LOGGER.info("No rows left without embeddings.");
                break;
            }

            LOGGER.info("Fetched " + descriptions.size()
                    + " rows without embeddings. totalUpdated=" + totalUpdated
                    + ", remainingAllowed=" + remainingRecords);

            List<List<BigDecimal>> embeddings = embeddingClient.createEmbeddings(
                    descriptions.stream()
                            .map(SyntheticDescription::citizenDescription)
                            .collect(Collectors.toList())
            );

            updateEmbeddings(config, descriptions, embeddings);
            totalUpdated += descriptions.size();
            int currentTotalUpdated = totalUpdated;
            LOGGER.info(() -> "Updated " + descriptions.size()
                    + " rows in this batch; " + currentTotalUpdated + " total.");
        }

        LOGGER.info("Embedding backfill finished. totalUpdated=" + totalUpdated
                + ", maxTotalRecordsToProcess=" + MAX_TOTAL_RECORDS_TO_PROCESS);
    }

    private static List<SyntheticDescription> fetchRowsWithoutEmbeddings(AppConfig config, int limit) throws SQLException {
        String sql = """
                SELECT synthetic_id, citizen_description
                FROM synthetic_routing_examples
                WHERE embedding IS NULL
                  AND citizen_description IS NOT NULL
                ORDER BY synthetic_id
                LIMIT ?
                """;

        List<SyntheticDescription> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                config.databaseUrl(),
                config.databaseUsername(),
                config.databasePassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new SyntheticDescription(
                            resultSet.getString("synthetic_id"),
                            resultSet.getString("citizen_description")
                    ));
                }
            }
        }
        return rows;
    }

    private static void updateEmbeddings(
            AppConfig config,
            List<SyntheticDescription> descriptions,
            List<List<BigDecimal>> embeddings
    ) throws SQLException {
        if (descriptions.size() != embeddings.size()) {
            throw new IllegalStateException("Expected " + descriptions.size()
                    + " embeddings, but OpenAI returned " + embeddings.size());
        }

        String sql = """
                UPDATE synthetic_routing_examples
                SET embedding = ?::vector
                WHERE synthetic_id = ?
                """;

        try (Connection connection = DriverManager.getConnection(
                config.databaseUrl(),
                config.databaseUsername(),
                config.databasePassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);

            for (int i = 0; i < descriptions.size(); i++) {
                statement.setString(1, toPgVectorLiteral(embeddings.get(i)));
                statement.setString(2, descriptions.get(i).syntheticId());
                statement.addBatch();
            }

            statement.executeBatch();
            connection.commit();
            LOGGER.info(() -> "Committed " + descriptions.size() + " embedding updates.");
        }
    }

    private static String toPgVectorLiteral(List<BigDecimal> embedding) {
        return embedding.stream()
                .map(BigDecimal::toPlainString)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private record SyntheticDescription(String syntheticId, String citizenDescription) {
    }

    private record AppConfig(
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            String openAiApiKey,
            String openAiEmbeddingModel,
            int batchSize
    ) {
        private static AppConfig load() throws IOException {
            Properties properties = new Properties();
            try (InputStream inputStream = EmbeddingBackfillApplication.class
                    .getClassLoader()
                    .getResourceAsStream("application.properties")) {
                if (inputStream == null) {
                    throw new IllegalStateException("Missing src/main/resources/application.properties");
                }
                properties.load(inputStream);
            }

            return new AppConfig(
                    properties.getProperty("database.url", "jdbc:postgresql://127.0.0.1:5432/servicerouterdb"),
                    properties.getProperty("database.username", "postgres"),
                    properties.getProperty("database.password", ""),
                    properties.getProperty("openai.api.key", ""),
                    properties.getProperty("openai.embedding.model", "text-embedding-3-small"),
                    Integer.parseInt(properties.getProperty("embedding.batch.size", "500"))
            );
        }

        private void validate() {
            if (openAiApiKey == null || openAiApiKey.isBlank() || "REPLACE_ME".equals(openAiApiKey)) {
                throw new IllegalStateException("Set openai.api.key in application.properties before running.");
            }
            if (batchSize < 1 || batchSize > 2048) {
                throw new IllegalStateException("embedding.batch.size must be between 1 and 2048.");
            }
        }
    }

    private static class EmbeddingClient {
        private final AppConfig config;
        private final HttpClient httpClient;

        private EmbeddingClient(AppConfig config) {
            this.config = config;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
        }

        private List<List<BigDecimal>> createEmbeddings(List<String> inputs) throws IOException, InterruptedException {
            EmbeddingRequest requestBody = new EmbeddingRequest(config.openAiEmbeddingModel(), inputs);
            String requestJson = JSON.writeValueAsString(requestBody);
            // LOGGER.info(() -> "OpenAI embeddings request body: " + requestJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/embeddings"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + config.openAiApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // LOGGER.info(() -> "OpenAI embeddings response status: " + response.statusCode());
            // LOGGER.info(() -> "OpenAI embeddings response body: " + response.body());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("OpenAI embeddings request failed with HTTP "
                        + response.statusCode() + ": " + response.body());
            }

            EmbeddingResponse embeddingResponse = JSON.readValue(response.body(), EmbeddingResponse.class);
            return embeddingResponse.data().stream()
                    .sorted((left, right) -> Integer.compare(left.index(), right.index()))
                    .map(EmbeddingData::embedding)
                    .toList();
        }
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(
            int index,
            @JsonProperty("embedding") List<BigDecimal> embedding
    ) {
    }
}
