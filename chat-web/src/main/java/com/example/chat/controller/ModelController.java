package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * <h2>模型列表 API</h2>
 *
 * <p>对外暴露 DB 中已启用的 chat 模型列表，供前端动态渲染模型切换菜单。</p>
 *
 * <p>数据来源：chat-core → {@code /internal/models} → {@code ModelConfigRepository.findAll()}</p>
 * <p>前端用法：启动时 GET 此接口，渲染模型下拉菜单，切换时传 {@code preferred_model_config_id}</p>
 */
@Tag(name = "模型管理", description = "可用模型列表查询（数据驱动，非硬编码）")
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);

    private final CoreClient coreClient;

    public ModelController(CoreClient coreClient) {
        this.coreClient = coreClient;
    }

    /**
     * 获取所有已启用的 chat 模型列表。
     *
     * <p>返回格式：{@code [{id, provider, model, modelType, priority, enabled}, ...]}</p>
     * <p>前端根据返回数量动态渲染模型切换菜单，配置多少个就展示多少个。</p>
     */
    @Operation(summary = "获取可用模型列表", description = "返回 DB 中已启用的 chat 模型，供前端动态渲染切换菜单")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        try {
            Object result = coreClient.listModels();
            if (result instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> models = (List<Map<String, Object>>) result;
                return ResponseEntity.ok(models);
            }
            return ResponseEntity.ok(List.of());
        } catch (Exception e) {
            log.warn("[ModelController] 获取模型列表失败: {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }
}
