package com.serviceticketrouter;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.serviceticketrouter.embeddings.OpenAiEmbeddingClient;
import com.serviceticketrouter.embeddings.PgVector;

public class EmbeddingBackfillApplication {
    private static final Logger LOGGER = Logger.getLogger(EmbeddingBackfillApplication.class.getName());
    private static final int MAX_TOTAL_RECORDS_TO_PROCESS = 20000;

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        config.validate();

        OpenAiEmbeddingClient embeddingClient = new OpenAiEmbeddingClient(
                config.openAiApiKey(),
                config.openAiEmbeddingModel(),
                java.net.http.HttpClient.newHttpClient()
        );

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
                statement.setString(1, PgVector.toLiteral(embeddings.get(i)));
                statement.setString(2, descriptions.get(i).syntheticId());
                statement.addBatch();
            }

            statement.executeBatch();
            connection.commit();
            LOGGER.info(() -> "Committed " + descriptions.size() + " embedding updates.");
        }
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
}
