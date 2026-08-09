package com.example.chat.featureflag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 特性开关管理接口
 * 仅admin可访问（生产环境建议加权限校验）
 */
@RestController
@RequestMapping("/api/v1/feature-flags")
public class FeatureFlagController {

    @Autowired
    private FeatureFlagService featureFlagService;

    /** 获取所有开关状态 */
    @GetMapping
    public Map<String, Object> list() {
        return featureFlagService.getAllStatus();
    }

    /** 检查开关是否启用 */
    @GetMapping("/{name}")
    public Map<String, Object> check(@PathVariable String name,
                                      @RequestParam(required = false) String userId) {
        return Map.of(
                "name", name,
                "enabled", featureFlagService.isEnabled(name, userId),
                "userId", userId != null ? userId : "anonymous"
        );
    }

    /** 快速开关 */
    @PostMapping("/{name}/toggle")
    public Map<String, Object> toggle(@PathVariable String name,
                                       @RequestParam boolean enabled) {
        featureFlagService.toggle(name, enabled);
        return Map.of("name", name, "enabled", enabled, "message", "已更新");
    }

    /** 设置灰度百分比 */
    @PostMapping("/{name}/percentage")
    public Map<String, Object> setPercentage(@PathVariable String name,
                                              @RequestParam int percentage) {
        featureFlagService.setPercentage(name, percentage);
        return Map.of("name", name, "percentage", percentage, "message", "灰度比例已设置");
    }

    /** 添加白名单用户 */
    @PostMapping("/{name}/whitelist/{userId}")
    public Map<String, Object> addWhitelist(@PathVariable String name,
                                             @PathVariable String userId) {
        featureFlagService.addWhitelist(name, userId);
        return Map.of("name", name, "userId", userId, "message", "已加入白名单");
    }

    /** 清除缓存 */
    @PostMapping("/clear-cache")
    public Map<String, Object> clearCache() {
        featureFlagService.clearCache();
        return Map.of("message", "缓存已清除");
    }
}
