package io.jburrow.server.api.instance.adapter.web;

import io.jburrow.server.api.instance.application.DescribeInstance;
import io.jburrow.server.api.shared.web.ApiResponse;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/meta")
public class MetaController {

    private final DescribeInstance describeInstance;

    public MetaController(DescribeInstance describeInstance) {
        this.describeInstance = Objects.requireNonNull(describeInstance, "describeInstance");
    }

    @GetMapping
    public ApiResponse<MetaResponse> meta() {
        return ApiResponse.ok("Instance", MetaResponse.from(describeInstance.execute()));
    }
}
