package com.serviceticketrouter.queue;

import java.time.LocalDateTime;

public record System311QueueResponse(
        long queueId,
        String description,
        String address,
        String predictedDepartment,
        String predictedServiceRequestType,
        String predictedPriority,
        Double routingConfidence,
        String status,
        String statusReason,
        String serviceRequestNumber,
        String departmentTicketId,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
