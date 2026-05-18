package com.serviceticketrouter.routes;

import com.serviceticketrouter.queue.System311QueueCreateRequest;
import com.serviceticketrouter.queue.System311QueueResponse;
import com.serviceticketrouter.queue.System311QueueService;
import org.springframework.stereotype.Service;
import org.springaicommunity.mcp.annotation.McpTool;

@Service
public class ServiceTicketTools {
    private final RoutePredictionService routePredictionService;
    private final System311QueueService system311QueueService;

    public ServiceTicketTools(
            RoutePredictionService routePredictionService,
            System311QueueService system311QueueService
    ) {
        this.routePredictionService = routePredictionService;
        this.system311QueueService = system311QueueService;
    }

    @McpTool(
            name = "predictRoute",
            description = "Predict the correct department, service request type, and priority for a citizen complaint"
    )
    public RoutePredictionResponse predictRoute(RoutePredictionRequest request) {
        return routePredictionService.predict(request.description());
    }

    @McpTool(
            name = "create311QueueItem",
            description = """
                    Creates a new intake/workflow item in the 311 operations queue.

                    This tool is used when a citizen complaint has been received but has not yet fully completed operational dispatch processing.

                    The queue represents the intake/orchestration layer of the 311 system before or during downstream department routing.

                    Valid statuses:

                    Received:
                    Initial intake item has been created but routing/dispatch decisions may not yet be complete.

                    NeedsReview:
                    The AI routing confidence was too low or the complaint was operationally ambiguous and requires human review.

                    ReadyForDispatch:
                    The routing confidence is high enough and the complaint is ready to be dispatched into a downstream departmental workflow.

                    NeedsDispatch:
                    The route prediction is acceptable, but no automated department dispatch tool/integration exists, requiring manual dispatch handling.

                    Dispatched:
                    A downstream department ticket/work order was successfully created and linked to the queue item.

                    DispatchFailed:
                    An attempt was made to create the downstream departmental ticket/work order, but the dispatch operation failed.

                    Closed:
                    The queue workflow has completed and no additional orchestration work is pending.
                    """
    )
    public System311QueueResponse create311QueueItem(System311QueueCreateRequest request) {
        return system311QueueService.create(request);
    }
}
