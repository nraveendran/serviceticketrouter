package com.serviceticketrouter.embeddings;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenAiEmbeddingClient {
    private static final Logger LOGGER = Logger.getLogger(OpenAiEmbeddingClient.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    @Autowired
    public OpenAiEmbeddingClient(
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.embedding.model:text-embedding-3-small}") String model
    ) {
        this(apiKey, model, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build());
    }

    public OpenAiEmbeddingClient(String apiKey, String model, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = httpClient;
    }

    public List<BigDecimal> createEmbedding(String input) throws IOException, InterruptedException {
        List<List<BigDecimal>> embeddings = createEmbeddings(List.of(input));
        if (embeddings.isEmpty()) {
            throw new IOException("OpenAI returned no embeddings.");
        }
        return embeddings.get(0);
    }

    public List<List<BigDecimal>> createEmbeddings(List<String> inputs) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank() || "REPLACE_ME".equals(apiKey)) {
            throw new IllegalStateException("Set openai.api.key in application.properties before running.");
        }

        EmbeddingRequest requestBody = new EmbeddingRequest(model, inputs);
        String requestJson = JSON.writeValueAsString(requestBody);
        LOGGER.info(() -> "OpenAI embeddings request inputCount=" + inputs.size() + ", model=" + model);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/embeddings"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        LOGGER.info(() -> "OpenAI embeddings response status=" + response.statusCode());

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
