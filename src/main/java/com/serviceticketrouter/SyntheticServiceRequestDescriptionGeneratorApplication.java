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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SyntheticServiceRequestDescriptionGeneratorApplication {
    private static final Logger LOGGER = Logger.getLogger(SyntheticServiceRequestDescriptionGeneratorApplication.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TARGET_TOTAL_DESCRIPTIONS = 10000;
    private static final int MIN_PER_CATEGORY = 20;
    private static final int MAX_PER_CATEGORY = 100;
    private static final int MAX_OPENAI_CALLS_PER_CATEGORY = 5;
    private static final String PROMPT_FILE = "complaintgenerationprompt.txt";

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        config.validate();

        String promptTemplate = readResource(PROMPT_FILE);
        List<MetadataRow> metadataRows = fetchMetadataRows(config);
        Map<CategoryKey, Integer> requestCounts = fetchRequestCounts(config);
        List<Allocation> allocationPlan = calculateAllocations(metadataRows, requestCounts, TARGET_TOTAL_DESCRIPTIONS);

        LOGGER.info(() -> "Loaded metadataRows=" + metadataRows.size()
                + ", requestCountCategories=" + requestCounts.size()
                + ", allocationCategories=" + allocationPlan.size()
                + ", allocatedTotal=" + allocationPlan.stream().mapToInt(Allocation::allocationCount).sum());

        OpenAiJsonArrayClient openAiClient = new OpenAiJsonArrayClient(config);
        int insertedTotal = 0;
        int skippedCategories = 0;
        int failedCategories = 0;

        for (Allocation allocation : allocationPlan) {
            if (allocation.allocationCount() <= 0) {
                continue;
            }

            List<SourceRequest> sampledRequests = sampleRequests(config, allocation);
            if (sampledRequests.isEmpty()) {
                skippedCategories++;
                LOGGER.info(() -> "Skipping category with no unsynthesized source rows: " + allocation.summary());
                continue;
            }

            int actualCount = sampledRequests.size();
            LOGGER.info(() -> "Generating " + actualCount + " descriptions for " + allocation.summary());

            try {
                List<GeneratedDescription> descriptions = generateDescriptionsUntilComplete(
                        openAiClient,
                        promptTemplate,
                        allocation.metadataRow(),
                        actualCount
                );
                int inserted = insertDescriptions(config, sampledRequests, descriptions);
                insertedTotal += inserted;

                LOGGER.info("Inserted " + inserted + " synthetic descriptions for " + allocation.summary()
                        + ". insertedTotal=" + insertedTotal);
            } catch (Exception e) {
                failedCategories++;
                LOGGER.log(Level.WARNING, "Failed category: " + allocation.summary(), e);
            }
        }

        LOGGER.info("Synthetic description generation finished. insertedTotal=" + insertedTotal
                + ", skippedCategories=" + skippedCategories
                + ", failedCategories=" + failedCategories);
    }

    private static List<MetadataRow> fetchMetadataRows(AppConfig config) throws SQLException {
        String sql = """
                SELECT
                    service_request_type,
                    department,
                    priority,
                    metadata_json
                FROM service_type_metadata
                WHERE metadata_json IS NOT NULL
                  AND metadata_json <> '{}'::jsonb
                ORDER BY service_request_type, department, priority
                """;

        List<MetadataRow> rows = new ArrayList<>();
        try (Connection connection = openConnection(config);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(new MetadataRow(
                        resultSet.getString("service_request_type"),
                        resultSet.getString("department"),
                        resultSet.getString("priority"),
                        resultSet.getString("metadata_json")
                ));
            }
        }
        return rows;
    }

    private static Map<CategoryKey, Integer> fetchRequestCounts(AppConfig config) throws SQLException {
        String sql = """
                SELECT
                    service_request_type,
                    department,
                    priority,
                    COUNT(*) AS request_count
                FROM service_requests
                GROUP BY service_request_type, department, priority
                """;

        Map<CategoryKey, Integer> counts = new HashMap<>();
        try (Connection connection = openConnection(config);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                counts.put(
                        new CategoryKey(
                                resultSet.getString("service_request_type"),
                                resultSet.getString("department"),
                                resultSet.getString("priority")
                        ),
                        resultSet.getInt("request_count")
                );
            }
        }
        return counts;
    }

    private static List<Allocation> calculateAllocations(
            List<MetadataRow> metadataRows,
            Map<CategoryKey, Integer> requestCounts,
            int targetTotal
    ) {
        List<AllocationSeed> eligible = metadataRows.stream()
                .map(row -> new AllocationSeed(row, requestCounts.getOrDefault(row.key(), 0)))
                .filter(seed -> seed.requestCount() > 0)
                .toList();

        if (eligible.isEmpty()) {
            return List.of();
        }

        int baseAllocation = targetTotal >= eligible.size() * MIN_PER_CATEGORY ? MIN_PER_CATEGORY : 0;
        int remaining = Math.max(0, targetTotal - eligible.size() * baseAllocation);
        double totalWeight = eligible.stream()
                .mapToDouble(seed -> Math.log1p(seed.requestCount()))
                .sum();

        List<Allocation> allocations = new ArrayList<>();
        for (AllocationSeed seed : eligible) {
            int extra = totalWeight == 0.0
                    ? 0
                    : (int) Math.round(remaining * Math.log1p(seed.requestCount()) / totalWeight);
            int allocationCount = baseAllocation + extra;
            allocationCount = Math.min(allocationCount, MAX_PER_CATEGORY);
            allocationCount = Math.min(allocationCount, seed.requestCount());
            allocations.add(new Allocation(seed.metadataRow(), seed.requestCount(), allocationCount));
        }

        adjustAllocations(allocations, targetTotal);
        return allocations.stream()
                .sorted(Comparator.comparing(allocation -> allocation.metadataRow().serviceRequestType()))
                .toList();
    }

    private static void adjustAllocations(List<Allocation> allocations, int targetTotal) {
        while (sumAllocations(allocations) < targetTotal) {
            Allocation candidate = allocations.stream()
                    .filter(allocation -> allocation.allocationCount() < MAX_PER_CATEGORY)
                    .filter(allocation -> allocation.allocationCount() < allocation.requestCount())
                    .max(Comparator.comparingInt(allocation -> allocation.requestCount() - allocation.allocationCount()))
                    .orElse(null);
            if (candidate == null) {
                break;
            }
            replaceAllocation(allocations, candidate, candidate.allocationCount() + 1);
        }

        while (sumAllocations(allocations) > targetTotal) {
            Allocation candidate = allocations.stream()
                    .filter(allocation -> allocation.allocationCount() > 0)
                    .min(Comparator.comparingInt(Allocation::requestCount))
                    .orElse(null);
            if (candidate == null) {
                break;
            }
            replaceAllocation(allocations, candidate, candidate.allocationCount() - 1);
        }
    }

    private static int sumAllocations(List<Allocation> allocations) {
        return allocations.stream().mapToInt(Allocation::allocationCount).sum();
    }

    private static void replaceAllocation(List<Allocation> allocations, Allocation oldValue, int newAllocationCount) {
        int index = allocations.indexOf(oldValue);
        allocations.set(index, new Allocation(oldValue.metadataRow(), oldValue.requestCount(), newAllocationCount));
    }

    private static List<SourceRequest> sampleRequests(AppConfig config, Allocation allocation) throws SQLException {
        String sql = """
                SELECT
                    service_request_number,
                    service_request_type,
                    department,
                    priority,
                    address,
                    city_council_district,
                    method_received_description,
                    created_at
                FROM service_requests sr
                WHERE sr.service_request_type IS NOT DISTINCT FROM ?
                  AND sr.department IS NOT DISTINCT FROM ?
                  AND sr.priority IS NOT DISTINCT FROM ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM synthetic_service_request_descriptions s
                      WHERE s.service_request_number = sr.service_request_number
                  )
                ORDER BY random()
                LIMIT ?
                """;

        List<SourceRequest> requests = new ArrayList<>();
        try (Connection connection = openConnection(config);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, allocation.metadataRow().serviceRequestType());
            statement.setString(2, allocation.metadataRow().department());
            statement.setString(3, allocation.metadataRow().priority());
            statement.setInt(4, allocation.allocationCount());

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    requests.add(new SourceRequest(
                            resultSet.getString("service_request_number"),
                            resultSet.getString("service_request_type"),
                            resultSet.getString("department"),
                            resultSet.getString("priority"),
                            resultSet.getString("address"),
                            resultSet.getString("city_council_district"),
                            resultSet.getString("method_received_description"),
                            toLocalDateTime(resultSet.getTimestamp("created_at"))
                    ));
                }
            }
        }
        return requests;
    }

    private static List<GeneratedDescription> generateDescriptionsUntilComplete(
            OpenAiJsonArrayClient openAiClient,
            String promptTemplate,
            MetadataRow metadataRow,
            int expectedCount
    ) throws IOException, InterruptedException {
        List<GeneratedDescription> allDescriptions = new ArrayList<>();
        int failedValidationCount = 0;
        int openAiCallCount = 0;

        while (allDescriptions.size() < expectedCount && openAiCallCount < MAX_OPENAI_CALLS_PER_CATEGORY) {
            int remainingCount = expectedCount - allDescriptions.size();
            String prompt = buildPrompt(promptTemplate, metadataRow, remainingCount);
            int nextCallNumber = openAiCallCount + 1;
            LOGGER.info(() -> "Calling OpenAI for " + remainingCount
                    + " remaining descriptions. generatedSoFar=" + allDescriptions.size()
                    + ", expectedCount=" + expectedCount
                    + ", openAiCall=" + nextCallNumber
                    + ", maxOpenAiCalls=" + MAX_OPENAI_CALLS_PER_CATEGORY);

            List<GeneratedDescription> generatedDescriptions;
            try {
                openAiCallCount++;
                generatedDescriptions = openAiClient.generateDescriptions(prompt, remainingCount);
            } catch (InvalidGeneratedDescriptionsException e) {
                failedValidationCount++;
                if (failedValidationCount > 1) {
                    throw e;
                }
                LOGGER.warning(() -> "Generated descriptions failed validation. Retrying once for the same remainder. reason="
                        + e.getMessage());
                continue;
            }

            if (generatedDescriptions.isEmpty()) {
                throw new InvalidGeneratedDescriptionsException("OpenAI returned zero valid descriptions.");
            }

            if (generatedDescriptions.size() > remainingCount) {
                LOGGER.warning("OpenAI returned more descriptions than requested. requested="
                        + remainingCount + ", returned=" + generatedDescriptions.size()
                        + ". Trimming extras.");
                generatedDescriptions = generatedDescriptions.subList(0, remainingCount);
            }

            allDescriptions.addAll(generatedDescriptions);

            if (generatedDescriptions.size() < remainingCount) {
                LOGGER.warning("OpenAI returned fewer descriptions than requested. requested="
                        + remainingCount + ", returned=" + generatedDescriptions.size()
                        + ", nextRequestCount=" + (expectedCount - allDescriptions.size()));
            }
        }

        if (allDescriptions.size() > expectedCount) {
            return allDescriptions.subList(0, expectedCount);
        }
        if (allDescriptions.size() < expectedCount) {
            LOGGER.warning("Could not collect the full requested count after OpenAI calls. expected="
                    + expectedCount + ", collected=" + allDescriptions.size()
                    + ", openAiCalls=" + openAiCallCount);
        }
        return allDescriptions;
    }

    private static int insertDescriptions(
            AppConfig config,
            List<SourceRequest> sampledRequests,
            List<GeneratedDescription> descriptions
    ) throws SQLException {
        int insertCount = Math.min(sampledRequests.size(), descriptions.size());
        if (insertCount < sampledRequests.size()) {
            LOGGER.warning(() -> "Generated fewer descriptions than source rows. sourceRows="
                    + sampledRequests.size() + ", descriptions=" + descriptions.size()
                    + ". Inserting matched prefix only.");
        }

        String sql = """
                INSERT INTO synthetic_service_request_descriptions (
                    service_request_number,
                    service_request_type,
                    department,
                    priority,
                    generated_description,
                    difficulty
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (service_request_number) DO NOTHING
                """;

        int inserted = 0;
        try (Connection connection = openConnection(config);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);

            for (int i = 0; i < insertCount; i++) {
                SourceRequest sourceRequest = sampledRequests.get(i);
                GeneratedDescription generated = descriptions.get(i);
                statement.setString(1, sourceRequest.serviceRequestNumber());
                statement.setString(2, nullToEmpty(sourceRequest.serviceRequestType()));
                statement.setString(3, nullToEmpty(sourceRequest.department()));
                statement.setString(4, nullToEmpty(sourceRequest.priority()));
                statement.setString(5, generated.description());
                statement.setString(6, generated.difficulty());
                statement.addBatch();
            }

            int[] batchResults = statement.executeBatch();
            connection.commit();

            for (int result : batchResults) {
                if (result > 0 || result == PreparedStatement.SUCCESS_NO_INFO) {
                    inserted++;
                }
            }
        }
        return inserted;
    }

    private static String buildPrompt(
            String promptTemplate,
            MetadataRow metadataRow,
            int count
    ) {
        return promptTemplate
                .replace("{{count}}", Integer.toString(count))
                .replace("{{metadata_json}}", metadataRow.metadataJson())
                .replace("{{service_request_type}}", nullToEmpty(metadataRow.serviceRequestType()))
                .replace("{{department}}", nullToEmpty(metadataRow.department()))
                .replace("{{priority}}", nullToEmpty(metadataRow.priority()));
    }

    private static String readResource(String resourceName) throws IOException {
        try (InputStream inputStream = SyntheticServiceRequestDescriptionGeneratorApplication.class
                .getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing src/main/resources/" + resourceName);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Connection openConnection(AppConfig config) throws SQLException {
        return DriverManager.getConnection(
                config.databaseUrl(),
                config.databaseUsername(),
                config.databasePassword()
        );
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record CategoryKey(String serviceRequestType, String department, String priority) {
    }

    private record MetadataRow(
            String serviceRequestType,
            String department,
            String priority,
            String metadataJson
    ) {
        private CategoryKey key() {
            return new CategoryKey(serviceRequestType, department, priority);
        }
    }

    private record AllocationSeed(MetadataRow metadataRow, int requestCount) {
    }

    private record Allocation(MetadataRow metadataRow, int requestCount, int allocationCount) {
        private String summary() {
            return "serviceRequestType=\"" + metadataRow.serviceRequestType()
                    + "\", department=\"" + metadataRow.department()
                    + "\", priority=\"" + metadataRow.priority()
                    + "\", allocationCount=" + allocationCount
                    + ", requestCount=" + requestCount;
        }
    }

    private record SourceRequest(
            String serviceRequestNumber,
            String serviceRequestType,
            String department,
            String priority,
            String address,
            String cityCouncilDistrict,
            String methodReceivedDescription,
            LocalDateTime createdAt
    ) {
    }

    private record GeneratedDescription(String description, String difficulty) {
    }

    private record AppConfig(
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            String openAiApiKey,
            String openAiComplaintGenerationModel
    ) {
        private static AppConfig load() throws IOException {
            Properties properties = new Properties();
            try (InputStream inputStream = SyntheticServiceRequestDescriptionGeneratorApplication.class
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
                    properties.getProperty("openai.complaint.generation.model", "gpt-4o-mini")
            );
        }

        private void validate() {
            if (openAiApiKey == null || openAiApiKey.isBlank() || "REPLACE_ME".equals(openAiApiKey)) {
                throw new IllegalStateException("Set openai.api.key in application.properties before running.");
            }
        }
    }

    private static class OpenAiJsonArrayClient {
        private final AppConfig config;
        private final HttpClient httpClient;

        private OpenAiJsonArrayClient(AppConfig config) {
            this.config = config;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
        }

        private List<GeneratedDescription> generateDescriptions(String prompt, int expectedCount)
                throws IOException, InterruptedException {
            Map<String, Object> requestBody = Map.of(
                    "model", config.openAiComplaintGenerationModel(),
                    "temperature", 0.8,
                    "messages", List.of(
                            Map.of(
                                    "role", "system",
                                    "content", "Return only valid JSON with a descriptions array. Do not wrap it in markdown."
                            ),
                            Map.of(
                                    "role", "user",
                                    "content", prompt
                            )
                    )
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .timeout(Duration.ofMinutes(3))
                    .header("Authorization", "Bearer " + config.openAiApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("OpenAI complaint generation request failed with HTTP "
                        + response.statusCode() + ": " + response.body());
            }

            JsonNode responseJson = JSON.readTree(response.body());
            JsonNode content = responseJson.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new InvalidGeneratedDescriptionsException("OpenAI response did not contain message content.");
            }

            return parseDescriptions(content.asText(), expectedCount);
        }

        private List<GeneratedDescription> parseDescriptions(String content, int expectedCount) throws IOException {
            JsonNode root = JSON.readTree(stripMarkdownFence(content));
            JsonNode descriptionsJson = root.isArray() ? root : root.get("descriptions");
            if (descriptionsJson == null || !descriptionsJson.isArray()) {
                throw new InvalidGeneratedDescriptionsException("Response must be a JSON array or an object with a descriptions array.");
            }
            if (descriptionsJson.size() > expectedCount) {
                LOGGER.warning(() -> "Response contained more descriptions than requested. requested="
                        + expectedCount + ", received=" + descriptionsJson.size());
            }

            List<GeneratedDescription> descriptions = new ArrayList<>();
            for (JsonNode item : descriptionsJson) {
                JsonNode descriptionNumber = item.get("description_number");
                JsonNode description = item.get("description");
                JsonNode difficulty = item.get("difficulty");

                if (descriptionNumber == null || !descriptionNumber.canConvertToInt() || descriptionNumber.asInt() < 1) {
                    throw new InvalidGeneratedDescriptionsException("Each item must have a positive integer description_number.");
                }
                if (description == null || !description.isTextual() || description.asText().isBlank()) {
                    throw new InvalidGeneratedDescriptionsException("Each item must have a non-empty description.");
                }
                if (difficulty == null || !difficulty.isTextual() || difficulty.asText().isBlank()) {
                    throw new InvalidGeneratedDescriptionsException("Each item must have a non-empty difficulty.");
                }

                descriptions.add(new GeneratedDescription(description.asText().trim(), difficulty.asText().trim()));
            }
            return descriptions;
        }

        private String stripMarkdownFence(String content) {
            String trimmed = content.trim();
            if (!trimmed.startsWith("```")) {
                return trimmed;
            }

            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline < 0 || lastFence <= firstNewline) {
                return trimmed;
            }
            return trimmed.substring(firstNewline + 1, lastFence).trim();
        }
    }

    private static class InvalidGeneratedDescriptionsException extends IOException {
        private InvalidGeneratedDescriptionsException(String message) {
            super(message);
        }
    }
}
