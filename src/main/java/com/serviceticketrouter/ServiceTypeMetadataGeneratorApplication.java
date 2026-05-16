package com.serviceticketrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServiceTypeMetadataGeneratorApplication {
    private static final Logger LOGGER = Logger.getLogger(ServiceTypeMetadataGeneratorApplication.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT_RESOURCE = "servicetype_metadata_prompt.txt";

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        config.validate();

        String promptTemplate = loadPromptTemplate();
        OpenAiJsonClient openAiClient = new OpenAiJsonClient(config);

        int seededCount = seedServiceTypeMetadataRows(config);
        LOGGER.info(() -> "Seeded " + seededCount + " new service_type_metadata rows.");

        List<ServiceTypeMetadataRow> metadataRows = fetchRowsWithEmptyMetadata(config);
        LOGGER.info(() -> "Found " + metadataRows.size() + " service_type_metadata rows with empty metadata_json.");

        int successCount = 0;
        int failureCount = 0;
        for (ServiceTypeMetadataRow metadataRow : metadataRows) {
            LOGGER.info(() -> "Generating metadata for serviceTypeMetadataId=" + metadataRow.id()
                    + ", serviceRequestType=\"" + metadataRow.serviceRequestType()
                    + "\", department=\"" + metadataRow.department()
                    + "\", priority=\"" + metadataRow.priority() + "\"");

            try {
                String prompt = fillPrompt(promptTemplate, metadataRow);
                JsonNode metadataJson = openAiClient.generateMetadataJson(prompt);
                validateMetadataJson(metadataJson);
                updateMetadata(config, metadataRow.id(), JSON.writeValueAsString(metadataJson));
                successCount++;

                LOGGER.info(() -> "Stored metadata for serviceTypeMetadataId=" + metadataRow.id()
                        + ", serviceRequestType=\"" + metadataRow.serviceRequestType() + "\"");
            } catch (Exception e) {
                failureCount++;
                LOGGER.log(
                        Level.WARNING,
                        "Failed to generate/store metadata for serviceTypeMetadataId=" + metadataRow.id()
                                + ", serviceRequestType=\"" + metadataRow.serviceRequestType()
                                + "\". It can be retried by running this job again.",
                        e
                );
            }
        }

        LOGGER.info("Metadata generation finished. successCount=" + successCount
                + ", failureCount=" + failureCount);
    }

    private static int seedServiceTypeMetadataRows(AppConfig config) throws SQLException {
        String sql = """
                INSERT INTO service_type_metadata (
                    service_request_type,
                    department,
                    priority,
                    metadata_json
                )
                SELECT DISTINCT
                    examples.service_request_type,
                    examples.department,
                    examples.priority,
                    '{}'::jsonb
                FROM synthetic_routing_examples examples
                WHERE examples.service_request_type IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM service_type_metadata metadata
                      WHERE metadata.service_request_type IS NOT DISTINCT FROM examples.service_request_type
                        AND metadata.department IS NOT DISTINCT FROM examples.department
                        AND metadata.priority IS NOT DISTINCT FROM examples.priority
                  )
                """;

        try (Connection connection = DriverManager.getConnection(
                config.databaseUrl(),
                config.databaseUsername(),
                config.databasePassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    private static List<ServiceTypeMetadataRow> fetchRowsWithEmptyMetadata(AppConfig config) throws SQLException {
        String sql = """
                SELECT id, service_request_type, department, priority
                FROM service_type_metadata
                WHERE metadata_json = '{}'::jsonb
                ORDER BY service_request_type, department, priority, id
                """;

        List<ServiceTypeMetadataRow> metadataRows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                config.databaseUrl(),
                config.databaseUsername(),
                config.databasePassword());
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                metadataRows.add(new ServiceTypeMetadataRow(
                        resultSet.getLong("id"),
                        resultSet.getString("service_request_type"),
                        resultSet.getString("department"),
                        resultSet.getString("priority")
                ));
            }
        }
        return metadataRows;
    }

    private static void updateMetadata(AppConfig config, long id, String metadataJson) throws SQLException {
        String sql = """
                UPDATE service_type_metadata
                SET metadata_json = ?::jsonb
                WHERE id = ?
                """;

        try (Connection connection = DriverManager.getConnection(
                config.databaseUrl(),
                config.databaseUsername(),
                config.databasePassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, metadataJson);
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    private static String loadPromptTemplate() throws IOException {
        try (InputStream inputStream = ServiceTypeMetadataGeneratorApplication.class
                .getClassLoader()
                .getResourceAsStream(PROMPT_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing src/main/resources/" + PROMPT_RESOURCE);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fillPrompt(String promptTemplate, ServiceTypeMetadataRow metadataRow) {
        return promptTemplate
                .replace("{{service_request_type}}", nullToEmpty(metadataRow.serviceRequestType()))
                .replace("{{department}}", nullToEmpty(metadataRow.department()))
                .replace("{{priority}}", nullToEmpty(metadataRow.priority()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void validateMetadataJson(JsonNode metadataJson) {
        if (metadataJson == null || !metadataJson.isObject()) {
            throw new IllegalArgumentException("Metadata response must be a JSON object.");
        }

        requireText(metadataJson, "service_request_type");
        requireText(metadataJson, "department");
        requireText(metadataJson, "priority");
        requireText(metadataJson, "category_summary");
        requireText(metadataJson, "problem_domain");
        requireArray(metadataJson, "primary_objects");
        requireArray(metadataJson, "symptoms");
        requireArray(metadataJson, "locations");
        requireArray(metadataJson, "citizen_intent_examples");
        requireArray(metadataJson, "common_phrases");
        requireArray(metadataJson, "negative_examples");
        requireArray(metadataJson, "not_this_categories");

        JsonNode difficultyPatterns = metadataJson.get("difficulty_patterns");
        if (difficultyPatterns == null || !difficultyPatterns.isObject()) {
            throw new IllegalArgumentException("Missing or invalid JSON object field: difficulty_patterns");
        }
        requireArray(difficultyPatterns, "easy");
        requireArray(difficultyPatterns, "medium");
        requireArray(difficultyPatterns, "hard");
    }

    private static void requireText(JsonNode json, String fieldName) {
        JsonNode field = json.get(fieldName);
        if (field == null || !field.isTextual() || field.asText().isBlank()) {
            throw new IllegalArgumentException("Missing or invalid text field: " + fieldName);
        }
    }

    private static void requireArray(JsonNode json, String fieldName) {
        JsonNode field = json.get(fieldName);
        if (field == null || !field.isArray()) {
            throw new IllegalArgumentException("Missing or invalid array field: " + fieldName);
        }
    }

    private record ServiceTypeMetadataRow(long id, String serviceRequestType, String department, String priority) {
    }

    private record AppConfig(
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            String openAiApiKey,
            String openAiMetadataModel
    ) {
        private static AppConfig load() throws IOException {
            Properties properties = new Properties();
            try (InputStream inputStream = ServiceTypeMetadataGeneratorApplication.class
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
                    properties.getProperty("openai.metadata.model", "gpt-4o-mini")
            );
        }

        private void validate() {
            if (openAiApiKey == null || openAiApiKey.isBlank() || "REPLACE_ME".equals(openAiApiKey)) {
                throw new IllegalStateException("Set openai.api.key in application.properties before running.");
            }
        }
    }

    private static class OpenAiJsonClient {
        private final AppConfig config;
        private final HttpClient httpClient;

        private OpenAiJsonClient(AppConfig config) {
            this.config = config;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
        }

        private JsonNode generateMetadataJson(String prompt) throws IOException, InterruptedException {
            Map<String, Object> requestBody = Map.of(
                    "model", config.openAiMetadataModel(),
                    "temperature", 0.2,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of(
                                    "role", "system",
                                    "content", "Return only valid JSON that conforms exactly to the requested schema."
                            ),
                            Map.of(
                                    "role", "user",
                                    "content", prompt
                            )
                    )
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + config.openAiApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("OpenAI metadata request failed with HTTP "
                        + response.statusCode() + ": " + response.body());
            }

            JsonNode responseJson = JSON.readTree(response.body());
            JsonNode content = responseJson.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new IOException("OpenAI metadata response did not contain message content.");
            }

            return JSON.readTree(content.asText());
        }
    }
}
