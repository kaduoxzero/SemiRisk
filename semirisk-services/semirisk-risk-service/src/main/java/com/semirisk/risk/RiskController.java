package com.semirisk.risk;

import com.semirisk.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/risk-score")
public class RiskController {

    private final RiskComputationService riskComputationService;

    public RiskController(RiskComputationService riskComputationService) {
        this.riskComputationService = riskComputationService;
    }

    @GetMapping("/today")
    public ApiResponse<RiskSnapshot> today() {
        return ApiResponse.ok(riskComputationService.snapshot());
    }

    @PostMapping("/recalculate")
    public ApiResponse<RiskSnapshot> recalculate() {
        riskComputationService.recalculate();
        return ApiResponse.ok("AI 风险测算完成", riskComputationService.snapshot());
    }
}

