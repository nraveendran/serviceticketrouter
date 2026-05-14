package com.serviceticketrouter.routes;

public record RoutePredictionResponse(
        String department,
        String serviceRequestType,
        String priority,
        double confidence
) {
}
