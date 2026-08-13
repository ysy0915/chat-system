package com.example.chat.featureflag;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 特性开关管理接口
 * 仅admin可访问（生产环境建议加权限校验）
 */
@Tag(name = "功能开关", description = "FeatureFlag 特性开关管理接口")
@RestController
@RequestMapping("/api/v1/feature-flags")
public class FeatureFlagController {

    @Autowired
    private FeatureFlagService featureFlagService;

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

    /** 快速开关 */
    @Operation(summary = "快速开关")
    @PostMapping("/{name}/toggle")
    public Map<String, Object> toggle(@Parameter(description = "开关名称") @PathVariable String name,
                                       @Parameter(description = "是否启用") @RequestParam boolean enabled) {
        featureFlagService.toggle(name, enabled);
        return Map.of("name", name, "enabled", enabled, "message", "已更新");
    }

    /** 设置灰度百分比 */
    @Operation(summary = "设置灰度百分比")
    @PostMapping("/{name}/percentage")
    public Map<String, Object> setPercentage(@Parameter(description = "开关名称") @PathVariable String name,
                                              @Parameter(description = "灰度百分比（0-100）") @RequestParam int percentage) {
        featureFlagService.setPercentage(name, percentage);
        return Map.of("name", name, "percentage", percentage, "message", "灰度比例已设置");
    }

    /** 添加白名单用户 */
    @Operation(summary = "添加白名单用户")
    @PostMapping("/{name}/whitelist/{userId}")
    public Map<String, Object> addWhitelist(@Parameter(description = "开关名称") @PathVariable String name,
                                             @Parameter(description = "用户ID") @PathVariable String userId) {
        featureFlagService.addWhitelist(name, userId);
        return Map.of("name", name, "userId", userId, "message", "已加入白名单");
    }

    /** 清除缓存 */
    @Operation(summary = "清除缓存")
    @PostMapping("/clear-cache")
    public Map<String, Object> clearCache() {
        featureFlagService.clearCache();
        return Map.of("message", "缓存已清除");
    }
}
