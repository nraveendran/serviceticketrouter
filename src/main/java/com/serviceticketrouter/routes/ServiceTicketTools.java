package com.serviceticketrouter.routes;

import org.springframework.stereotype.Service;
import org.springaicommunity.mcp.annotation.McpTool;

@Service
public class ServiceTicketTools {
    private final RoutePredictionService routePredictionService;

    public ServiceTicketTools(RoutePredictionService routePredictionService) {
        this.routePredictionService = routePredictionService;
    }

    @McpTool(
            name = "predictRoute",
            description = "Predict the correct department, service request type, and priority for a citizen complaint"
    )
    public RoutePredictionResponse predictRoute(RoutePredictionRequest request) {
        return routePredictionService.predict(request.description());
    }
}
