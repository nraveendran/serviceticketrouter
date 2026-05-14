package com.serviceticketrouter.routes;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/routes")
public class RoutePredictionController {
    private final RoutePredictionService routePredictionService;

    public RoutePredictionController(RoutePredictionService routePredictionService) {
        this.routePredictionService = routePredictionService;
    }

    @PostMapping("/predict")
    public RoutePredictionResponse predict(@RequestBody RoutePredictionRequest request) {
        if (request == null || request.description() == null || request.description().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description is required");
        }

        return routePredictionService.predict(request.description());
    }
}
