package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.SystemSettingsEntity;
import com.leoli.gateway.admin.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Console-wide feature switches; ADMIN can change them, every signed-in user can read them. */
@RestController
@RequestMapping("/api/system-settings")
@RequiredArgsConstructor
public class SystemSettingsController extends BaseController {

    private final SystemSettingsService settingsService;

    @GetMapping
    public ResponseEntity<ApiResponse<SystemSettingsEntity>> get() {
        return ok(settingsService.get());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ApiResponse<SystemSettingsEntity>> update(@RequestBody Map<String, Object> body) {
        SystemSettingsEntity saved = settingsService.update(
                optionalBoolean(body, "stressTestEnabled"),
                optionalBoolean(body, "middlewareEnabled"),
                optionalBoolean(body, "pluginsEnabled"),
                optionalBoolean(body, "kubernetesEnabled"),
                getOperator());
        return ok(saved, "Feature switches saved; navigation updates on the next load");
    }

    private static Boolean optionalBoolean(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof Boolean bool ? bool : null;
    }
}