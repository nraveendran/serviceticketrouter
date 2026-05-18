package com.serviceticketrouter.queue;

public record System311QueueCreateRequest(
        String description,
        String address,
        String predictedDepartment,
        String predictedServiceRequestType,
        String predictedPriority,
        Double routingConfidence,
        String status,
        String statusReason,
        String departmentTicketId
) {
}
