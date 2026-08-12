package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.security.AdminAuthUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <h2>LLM 模型管理代理 Controller</h2>
 *
 * <p>前端无法直达 chat-llm 内部服务，此代理透传
 * /api/v1/llm/admin/providers 到 chat-llm（llmBaseUrl）。</p>
 *
 * <p><b>权限控制</b>：写操作（新增/更新/删除/重载）必须携带
 * <code>X-Admin-Pass</code> 头（与监控面板同源管理密码），
 * 校验失败返回 403；只读列表放行（apiKey 已脱敏）。</p>
 */
@Tag(name = "LLM 模型管理（代理）", description = "转发到 chat-llm 的模型自助管理面（写操作需管理员密码）")
@RestController
@RequestMapping("/api/v1/llm/admin")
public class LlmAdminProxyController {

    private static final Logger log = LoggerFactory.getLogger(LlmAdminProxyController.class);

    /** 管理密码请求头 */
    public static final String ADMIN_PASS_HEADER = "X-Admin-Pass";

    private final CoreClient coreClient;
    private final AdminAuthUtil adminAuthUtil;

    public LlmAdminProxyController(CoreClient coreClient, AdminAuthUtil adminAuthUtil) {
        this.coreClient = coreClient;
        this.adminAuthUtil = adminAuthUtil;
    }

    private String authHeader(HttpServletRequest request) {
        return request.getHeader("Authorization");
    }

    /**
     * 管理员密码校验：与监控面板同一管理密码（monitor.password）。
     */
    private boolean isAdmin(HttpServletRequest request) {
        String pass = request.getHeader(ADMIN_PASS_HEADER);
        return pass != null && adminAuthUtil.checkMonitorPassword(pass);
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", "无权限：需管理员密码（X-Admin-Pass）");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @Operation(summary = "管理员验证登录", description = "校验管理密码（与监控面板一致），通过后可进行配置操作")
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String pass = body.get("password");
        if (pass != null && adminAuthUtil.checkMonitorPassword(pass)) {
            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false, "message", "密码错误"));
    }

    @Operation(summary = "提供商列表（只读，apiKey 脱敏）")
    @GetMapping("/providers")
    public Object list(HttpServletRequest request) {
        return coreClient.listLlmProviders(authHeader(request));
    }

    @Operation(summary = "支持的调用类型（只读）")
    @GetMapping("/providers/types")
    public Object types(HttpServletRequest request) {
        return coreClient.listLlmProviderTypes(authHeader(request));
    }

    @Operation(summary = "新增提供商（需管理员）")
    @PostMapping("/providers")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(request)) {
            log.warn("[LLMAdmin] 无权限尝试新增提供商（来源: {}）", request.getRemoteAddr());
            return forbidden();
        }
        return ResponseEntity.ok(coreClient.createLlmProvider(body, authHeader(request)));
    }

    @Operation(summary = "更新提供商（需管理员）")
    @PutMapping("/providers/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        if (!isAdmin(request)) {
            log.warn("[LLMAdmin] 无权限尝试更新提供商 {}（来源: {}）", id, request.getRemoteAddr());
            return forbidden();
        }
        return ResponseEntity.ok(coreClient.updateLlmProvider(id, body, authHeader(request)));
    }

    @Operation(summary = "删除提供商（需管理员）")
    @DeleteMapping("/providers/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!isAdmin(request)) {
            log.warn("[LLMAdmin] 无权限尝试删除提供商 {}（来源: {}）", id, request.getRemoteAddr());
            return forbidden();
        }
        return ResponseEntity.ok(coreClient.deleteLlmProvider(id, authHeader(request)));
    }

    @Operation(summary = "全量重载（需管理员）")
    @PostMapping("/providers/reload")
    public ResponseEntity<?> reload(HttpServletRequest request) {
        if (!isAdmin(request)) {
            log.warn("[LLMAdmin] 无权限尝试全量重载（来源: {}）", request.getRemoteAddr());
            return forbidden();
        }
        return ResponseEntity.ok(coreClient.reloadLlmProviders(authHeader(request)));
    }
}
