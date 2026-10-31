package com.aitutor.agent;

import com.aitutor.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.aitutor.agent.AgentContracts.*;

@RestController
@RequestMapping("/internal/agent/v1")
public class AgentToolController {
    private final PythonAgentGateway gateway;
    public AgentToolController(PythonAgentGateway gateway) { this.gateway=gateway; }

    @PostMapping("/search")
    public Result<ToolResult> search(@Valid @RequestBody ToolRequest request,@RequestHeader("X-Tutor-Scope") String scope) {
        return Result.success(gateway.tool("search",request,scope));
    }
    @PostMapping("/read")
    public Result<ToolResult> read(@Valid @RequestBody ToolRequest request,@RequestHeader("X-Tutor-Scope") String scope) {
        return Result.success(gateway.tool("read",request,scope));
    }
}
