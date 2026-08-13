package com.example.chat.llm.llm.routing.db;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * <h2>LLM 模型管理面 API</h2>
 *
 * <p>模型/厂商自助接入：提供商增删改查、调用类型查询、全量重载。
 * 前端经 chat-web 代理（/api/v1/llm/admin/providers）。</p>
 *
 * <p><b>安全</b>：apiKey 永不回传（仅返回 hasApiKey 状态）；写操作支持
 * 可配置的纵深防御（<code>app.llm.admin-password</code> 配置后，
 * 需携带 <code>X-Admin-Pass</code> 请求头，与 chat-web 层校验同源）。</p>
 */
@Tag(name = "LLM 模型管理", description = "模型/厂商自助接入管理面")
@RestController
@RequestMapping("/api/v1/llm/admin/providers")
public class LlmProviderAdminController {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderAdminController.class);

    /** 管理密码请求头（与 chat-web 代理一致） */
    public static final String ADMIN_PASS_HEADER = "X-Admin-Pass";

    private final LlmProviderAdminService service;

    /** 纵深防御密码：配置后写操作必须携带 X-Admin-Pass（默认空 = 依赖外层 chat-web 校验） */
    @Value("${app.llm.admin-password:}")
    private String adminPassword;

    public LlmProviderAdminController(LlmProviderAdminService service) {
        this.service = service;
    }

    @Operation(summary = "提供商列表（注册中心实时视图，apiKey 不返回）")
    @GetMapping
    public Map<String, Object> list() {
        return service.listProviders();
    }

    @Operation(summary = "支持的调用类型（策略工厂 supportedTypes）")
    @GetMapping("/types")
    public Map<String, Object> types() {
        return service.listTypes();
    }

    @Operation(summary = "新增提供商")
    @PostMapping
    public Object create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireAdmin(request);
        return service.createProvider(body);
    }

    @Operation(summary = "更新提供商")
    @PutMapping("/{id}")
    public Object update(@PathVariable Long id,
                         @RequestBody Map<String, Object> body,
                         HttpServletRequest request) {
        requireAdmin(request);
        return service.updateProvider(id, body);
    }

    @Operation(summary = "删除提供商")
    @DeleteMapping("/{id}")
    public Object delete(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        return service.deleteProvider(id);
    }

    @Operation(summary = "全量重载（YAML 兜底 + DB 覆盖）")
    @PostMapping("/reload")
    public Object reload(HttpServletRequest request) {
        requireAdmin(request);
        return service.reload();
    }

    /**
     * 纵深防御：配置了 app.llm.admin-password 时校验请求头，防止绕过 chat-web 直连。
     */
    private void requireAdmin(HttpServletRequest request) {
        if (adminPassword == null || adminPassword.isBlank()) {
            return; // 未配置密码 = 信任外层 chat-web 校验（生产默认）
        }
        String pass = request.getHeader(ADMIN_PASS_HEADER);
        if (pass == null || !adminPassword.equals(pass)) {
            throw new IllegalArgumentException("无权限：缺少管理员口令");
        }
    }

    /**
     * 参数/业务校验异常统一返回 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegalArgument(IllegalArgumentException e) {
        log.warn("[LLMAdmin] 参数错误: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
