package com.serviceticketrouter.queue;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queue")
public class System311QueueController {
    private final System311QueueService system311QueueService;

    public System311QueueController(System311QueueService system311QueueService) {
        this.system311QueueService = system311QueueService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public System311QueueResponse create(@RequestBody System311QueueCreateRequest request) {
        return system311QueueService.create(request);
    }
}
