package com.serviceticketrouter.routes;

public record RoutePredictionCandidate(
        int rank,
        String department,
        String serviceRequestType,
        String priority,
        int votes,
        double bestSimilarity,
        double avgSimilarity,
        double score,
        double confidence
) {
}
