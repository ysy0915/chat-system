package com.example.chat.featureflag;

import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.security.AdminAuthUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 特性开关管理接口
 * 读接口（list/check）需登录即可；写接口（toggle/setPercentage/addWhitelist/clearCache）
 * 需 X-Admin-Password 管理员密码（与监控面板同源 monitor.password，常量时间比较）。
 */
@Tag(name = "功能开关", description = "FeatureFlag 特性开关管理接口")
@RestController
@RequestMapping("/api/v1/feature-flags")
public class FeatureFlagController {

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private AdminAuthUtil adminAuthUtil;

    /** 校验写操作的管理员密码，未通过返回 null 表示校验失败（由调用方返回 401） */
    private ResponseEntity<Map<String, Object>> adminGuard(String adminPassword) {
        if (adminPassword != null && adminAuthUtil.checkMonitorPassword(adminPassword)) {
            return null;
        }
        return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "管理员密码错误"));
    }

    /** 获取所有开关状态 */
    @Operation(summary = "获取所有开关状态")
    @GetMapping
    public Map<String, Object> list() {
        return featureFlagService.getAllStatus();
    }

    /** 检查开关是否启用 */
    @Operation(summary = "检查开关是否启用")
    @GetMapping("/{name}")
    public Map<String, Object> check(@Parameter(description = "开关名称") @PathVariable String name,
                                      @Parameter(description = "用户ID（可选）") @RequestParam(required = false) String userId) {
        return Map.of(
                "name", name,
                "enabled", featureFlagService.isEnabled(name, userId),
                "userId", userId != null ? userId : "anonymous"
        );
    }

    /** 快速开关（需管理员密码） */
    @Operation(summary = "快速开关")
    @PostMapping("/{name}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(
            @Parameter(description = "管理员密码") @RequestHeader(value = "X-Admin-Password", required = false) String adminPassword,
            @Parameter(description = "开关名称") @PathVariable String name,
            @Parameter(description = "是否启用") @RequestParam boolean enabled) {
        ResponseEntity<Map<String, Object>> guard = adminGuard(adminPassword);
        if (guard != null) return guard;
        featureFlagService.toggle(name, enabled);
        return ResponseEntity.ok(Map.of("name", name, "enabled", enabled, "message", "已更新"));
    }

    /** 设置灰度百分比（需管理员密码） */
    @Operation(summary = "设置灰度百分比")
    @PostMapping("/{name}/percentage")
    public ResponseEntity<Map<String, Object>> setPercentage(
            @Parameter(description = "管理员密码") @RequestHeader(value = "X-Admin-Password", required = false) String adminPassword,
            @Parameter(description = "开关名称") @PathVariable String name,
            @Parameter(description = "灰度百分比（0-100）") @RequestParam int percentage) {
        ResponseEntity<Map<String, Object>> guard = adminGuard(adminPassword);
        if (guard != null) return guard;
        featureFlagService.setPercentage(name, percentage);
        return ResponseEntity.ok(Map.of("name", name, "percentage", percentage, "message", "灰度比例已设置"));
    }

    /** 添加白名单用户（需管理员密码） */
    @Operation(summary = "添加白名单用户")
    @PostMapping("/{name}/whitelist/{userId}")
    public ResponseEntity<Map<String, Object>> addWhitelist(
            @Parameter(description = "管理员密码") @RequestHeader(value = "X-Admin-Password", required = false) String adminPassword,
            @Parameter(description = "开关名称") @PathVariable String name,
            @Parameter(description = "用户ID") @PathVariable String userId) {
        ResponseEntity<Map<String, Object>> guard = adminGuard(adminPassword);
        if (guard != null) return guard;
        featureFlagService.addWhitelist(name, userId);
        return ResponseEntity.ok(Map.of("name", name, "userId", userId, "message", "已加入白名单"));
    }

    /** 清除缓存（需管理员密码） */
    @Operation(summary = "清除缓存")
    @PostMapping("/clear-cache")
    public ResponseEntity<Map<String, Object>> clearCache(
            @Parameter(description = "管理员密码") @RequestHeader(value = "X-Admin-Password", required = false) String adminPassword) {
        ResponseEntity<Map<String, Object>> guard = adminGuard(adminPassword);
        if (guard != null) return guard;
        featureFlagService.clearCache();
        return ResponseEntity.ok(Map.of("message", "缓存已清除"));
    }
}
