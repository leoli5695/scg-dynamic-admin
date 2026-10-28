package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.service.TokenUsageReconciliationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/token-usage/events/{usageId}/reconciliation")
@RequiredArgsConstructor
public class TokenUsageReconciliationController extends BaseController {
    private final TokenUsageReconciliationService service;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable long usageId) {
        return ok(service.get(usageId));
    }
    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@PathVariable long usageId,
            @Valid @RequestBody TokenUsageReconciliationService.Correction correction) {
        service.create(usageId, correction, getOperator()); // Commit intent before any Redis write.
        return ok(service.retry(usageId));
    }
    @PostMapping("/retry")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retry(@PathVariable long usageId) {
        return ok(service.retry(usageId));
    }
}
