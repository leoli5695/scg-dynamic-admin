package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.AuthPolicyDefinition;
import com.leoli.gateway.admin.model.AuthPolicyEntity;
import com.leoli.gateway.admin.model.RouteAuthBindingEntity;
import com.leoli.gateway.admin.service.AuthPolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Authentication Policy Management Controller.
 * Provides CRUD operations for auth policies and route bindings.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthPolicyController {

    @Autowired
    private AuthPolicyService authPolicyService;

    // ==================== Policy CRUD ====================

    /**
     * Get all auth policies.
     * @param instanceId Optional instance ID to filter policies
     */
    @GetMapping("/policies")
    public ResponseEntity<Map<String, Object>> getAllPolicies(
            @RequestParam(required = false) String instanceId) {
        List<AuthPolicyDefinition> policies;
        if (instanceId != null && !instanceId.isEmpty()) {
            policies = authPolicyService.getAllPolicies(instanceId);
        } else {
            policies = authPolicyService.getAllPolicies();
        }
        return ok(policies);
    }

    /**
     * Get policy by ID.
     * @param policyId Policy ID
     * @param instanceId Optional instance ID
     */
    @GetMapping("/policies/{policyId}")
    public ResponseEntity<Map<String, Object>> getPolicyById(
            @PathVariable String policyId,
            @RequestParam(required = false) String instanceId) {
        AuthPolicyDefinition policy;
        if (instanceId != null && !instanceId.isEmpty()) {
            policy = authPolicyService.getPolicy(instanceId, policyId);
        } else {
            policy = authPolicyService.getPolicy(policyId);
        }
        if (policy == null) {
            return notFound("Policy not found: " + policyId);
        }
        return ok(policy);
    }

    /**
     * Get policies by auth type.
     * @param authType Auth type
     * @param instanceId Optional instance ID to filter policies
     */
    @GetMapping("/policies/type/{authType}")
    public ResponseEntity<Map<String, Object>> getPoliciesByType(
            @PathVariable String authType,
            @RequestParam(required = false) String instanceId) {
        List<AuthPolicyDefinition> policies;
        if (instanceId != null && !instanceId.isEmpty()) {
            policies = authPolicyService.getPoliciesByType(instanceId, authType.toUpperCase());
        } else {
            policies = authPolicyService.getPoliciesByType(authType.toUpperCase());
        }
        return ok(policies);
    }

    /**
     * Create a new auth policy.
     * @param policy Policy definition
     * @param instanceId Optional instance ID
     */
    @PostMapping("/policies")
    public ResponseEntity<Map<String, Object>> createPolicy(
            @RequestBody AuthPolicyDefinition policy,
            @RequestParam(required = false) String instanceId) {
        try {
            log.info("Creating auth policy: {} (instance: {})", policy.getPolicyName(), instanceId);
            AuthPolicyEntity entity = authPolicyService.createPolicy(instanceId, policy);
            return ok(authPolicyService.getPolicy(entity.getPolicyId()), "Policy created successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create policy: {}", e.getMessage());
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create policy", e);
            return error("Failed to create policy: " + e.getMessage());
        }
    }

    /**
     * Update an auth policy.
     */
    @PutMapping("/policies/{policyId}")
    public ResponseEntity<Map<String, Object>> updatePolicy(
            @PathVariable String policyId,
            @RequestBody AuthPolicyDefinition policy) {
        try {
            log.info("Updating auth policy: {}, apiKey: {}, apiKeyHeader: {}", policyId, policy.getApiKey(), policy.getApiKeyHeader());
            authPolicyService.updatePolicy(policyId, policy);
            return ok(authPolicyService.getPolicy(policyId), "Policy updated successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update policy: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update policy", e);
            return error("Failed to update policy: " + e.getMessage());
        }
    }

    /**
     * Delete an auth policy.
     */
    @DeleteMapping("/policies/{policyId}")
    public ResponseEntity<Map<String, Object>> deletePolicy(@PathVariable String policyId) {
        try {
            log.info("Deleting auth policy: {}", policyId);
            authPolicyService.deletePolicy(policyId);
            return ok(null, "Policy deleted successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete policy: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete policy", e);
            return error("Failed to delete policy: " + e.getMessage());
        }
    }

    /**
     * Enable an auth policy.
     */
    @PostMapping("/policies/{policyId}/enable")
    public ResponseEntity<Map<String, Object>> enablePolicy(@PathVariable String policyId) {
        try {
            log.info("Enabling auth policy: {}", policyId);
            authPolicyService.enablePolicy(policyId);
            return ok(null, "Policy enabled successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to enable policy: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to enable policy", e);
            return error("Failed to enable policy: " + e.getMessage());
        }
    }

    /**
     * Disable an auth policy.
     */
    @PostMapping("/policies/{policyId}/disable")
    public ResponseEntity<Map<String, Object>> disablePolicy(@PathVariable String policyId) {
        try {
            log.info("Disabling auth policy: {}", policyId);
            authPolicyService.disablePolicy(policyId);
            return ok(null, "Policy disabled successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to disable policy: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to disable policy", e);
            return error("Failed to disable policy: " + e.getMessage());
        }
    }

    // ==================== Route Binding Management ====================

    /**
     * Get all bindings.
     */
    @GetMapping("/bindings")
    public ResponseEntity<Map<String, Object>> getAllBindings() {
        List<RouteAuthBindingEntity> bindings = authPolicyService.getAllBindings();
        return ok(bindings);
    }

    /**
     * Get bindings for a policy.
     */
    @GetMapping("/bindings/policy/{policyId}")
    public ResponseEntity<Map<String, Object>> getBindingsForPolicy(@PathVariable String policyId) {
        List<RouteAuthBindingEntity> bindings = authPolicyService.getBindingsForPolicy(policyId);
        return ok(bindings);
    }

    /**
     * Get bindings for a route.
     */
    @GetMapping("/bindings/route/{routeId}")
    public ResponseEntity<Map<String, Object>> getBindingsForRoute(@PathVariable String routeId) {
        List<RouteAuthBindingEntity> bindings = authPolicyService.getBindingsForRoute(routeId);
        return ok(bindings);
    }

    /**
     * Get policies for a route.
     */
    @GetMapping("/policies/route/{routeId}")
    public ResponseEntity<Map<String, Object>> getPoliciesForRoute(@PathVariable String routeId) {
        List<AuthPolicyDefinition> policies = authPolicyService.getPoliciesForRoute(routeId);
        return ok(policies);
    }

    /**
     * Create a binding (bind policy to route).
     */
    @PostMapping("/bindings")
    public ResponseEntity<Map<String, Object>> createBinding(@RequestBody Map<String, Object> request) {
        try {
            String policyId = (String) request.get("policyId");
            String routeId = (String) request.get("routeId");
            Integer priority = request.get("priority") != null ? (Integer) request.get("priority") : 100;

            if (policyId == null || routeId == null) {
                return badRequest("policyId and routeId are required");
            }

            log.info("Creating binding: policy {} -> route {}", policyId, routeId);
            RouteAuthBindingEntity binding = authPolicyService.bindPolicyToRoute(policyId, routeId, priority);
            return ok(binding, "Binding created successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create binding: {}", e.getMessage());
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create binding", e);
            return error("Failed to create binding: " + e.getMessage());
        }
    }

    /**
     * Batch bind policies to a route.
     */
    @PostMapping("/bindings/batch")
    public ResponseEntity<Map<String, Object>> batchBind(@RequestBody Map<String, Object> request) {
        try {
            String routeId = (String) request.get("routeId");
            @SuppressWarnings("unchecked")
            List<String> policyIds = (List<String>) request.get("policyIds");

            if (routeId == null || policyIds == null || policyIds.isEmpty()) {
                return badRequest("routeId and policyIds are required");
            }

            log.info("Batch binding {} policies to route {}", policyIds.size(), routeId);
            List<RouteAuthBindingEntity> bindings = authPolicyService.batchBind(routeId, policyIds);
            return ok(bindings, "Batch binding created successfully");
        } catch (Exception e) {
            log.error("Failed to batch bind", e);
            return error("Failed to batch bind: " + e.getMessage());
        }
    }

    /**
     * Delete a binding by ID.
     */
    @DeleteMapping("/bindings/{bindingId}")
    public ResponseEntity<Map<String, Object>> deleteBinding(@PathVariable String bindingId) {
        try {
            log.info("Deleting binding: {}", bindingId);
            authPolicyService.deleteBinding(bindingId);
            return ok(null, "Binding deleted successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete binding: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete binding", e);
            return error("Failed to delete binding: " + e.getMessage());
        }
    }

    /**
     * Unbind policy from route.
     */
    @DeleteMapping("/bindings/policy/{policyId}/route/{routeId}")
    public ResponseEntity<Map<String, Object>> unbindPolicyFromRoute(
            @PathVariable String policyId,
            @PathVariable String routeId) {
        try {
            log.info("Unbinding policy {} from route {}", policyId, routeId);
            authPolicyService.unbindPolicyFromRoute(policyId, routeId);
            return ok(null, "Binding deleted successfully");
        } catch (Exception e) {
            log.error("Failed to unbind", e);
            return error("Failed to unbind: " + e.getMessage());
        }
    }

    /**
     * Enable a binding.
     */
    @PostMapping("/bindings/{bindingId}/enable")
    public ResponseEntity<Map<String, Object>> enableBinding(@PathVariable String bindingId) {
        try {
            log.info("Enabling binding: {}", bindingId);
            authPolicyService.enableBinding(bindingId);
            return ok(null, "Binding enabled successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to enable binding: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to enable binding", e);
            return error("Failed to enable binding: " + e.getMessage());
        }
    }

    /**
     * Disable a binding.
     */
    @PostMapping("/bindings/{bindingId}/disable")
    public ResponseEntity<Map<String, Object>> disableBinding(@PathVariable String bindingId) {
        try {
            log.info("Disabling binding: {}", bindingId);
            authPolicyService.disableBinding(bindingId);
            return ok(null, "Binding disabled successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to disable binding: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to disable binding", e);
            return error("Failed to disable binding: " + e.getMessage());
        }
    }

    /**
     * Count bindings for a policy.
     */
    @GetMapping("/policies/{policyId}/bindings/count")
    public ResponseEntity<Map<String, Object>> countBindingsForPolicy(@PathVariable String policyId) {
        long count = authPolicyService.countBindingsForPolicy(policyId);
        Map<String, Object> result = new HashMap<>();
        result.put("policyId", policyId);
        result.put("bindingCount", count);
        return ok(result);
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> ok(Object data, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", message);
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 404);
        result.put("message", message);
        return ResponseEntity.status(404).body(result);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", message);
        return ResponseEntity.status(400).body(result);
    }

    private ResponseEntity<Map<String, Object>> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("message", message);
        return ResponseEntity.status(500).body(result);
    }
}