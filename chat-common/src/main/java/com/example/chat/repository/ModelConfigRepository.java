package com.example.chat.repository;

import com.example.chat.entity.ModelConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <h2>LLM 模型配置仓储</h2>
 *
 * <p>[B档] 三源归一后改查 <code>llm_*</code> 新表，旧表 <code>model_configs</code> 退役
 * （仅作历史存档，不再读写）。运行时统一以新表为唯一数据源。</p>
 *
 * <p>对外暴露的 {@link ModelConfig} 视图语义与旧表完全一致：</p>
 * <ul>
 *   <li>id         ← <code>llm_model_config.id</code>（迁移时保持与旧 id 一致，Redis 个人绑定不失效）</li>
 *   <li>provider   ← <code>llm_provider_config.provider_name</code></li>
 *   <li>model      ← <code>llm_model_config.model_name</code></li>
 *   <li>apiKeyEncrypted ← <code>llm_provider_props</code>(prop_key='api_key').prop_value</li>
 *   <li>metaJson   ← 重建 <code>{"baseUrl":"..."}</code>（优先模型级 llm_model_props.base_url，其次提供商 base_url）</li>
 *   <li>modelType  ← <code>llm_model_config.model_type</code>（沿用旧表枚举：chat / image / video / 3d / text_parse / image_parse）</li>
 * </ul>
 *
 * <p>表结构见 <code>docs/sql/llm_routing_schema.sql</code>，
 * 数据迁移见 <code>docs/sql/migrate_model_configs_to_llm.sql</code>。</p>
 */
@Mapper
public interface ModelConfigRepository {

    /** 公共查询列：重建 ModelConfig 视图 */
    String SELECT_COLS = "SELECT m.id AS id, p.provider_name AS provider, m.model_name AS model, " +
            "pk.prop_value AS apiKeyEncrypted, " +
            "CONCAT('{\"baseUrl\":\"', IFNULL(mb.prop_value, p.base_url), '\"}') AS metaJson, " +
            "m.priority AS priority, m.enabled AS enabled, m.model_type AS modelType, m.created_at AS createdAt ";

    /** 公共表连接：模型 × 提供商 × 提供商 api_key 属性 × 模型 base_url 覆盖属性 */
    String FROM_JOIN = "FROM llm_model_config m " +
            "JOIN llm_provider_config p ON m.provider_config_id = p.id " +
            "LEFT JOIN llm_provider_props pk ON pk.provider_config_id = p.id AND pk.prop_key = 'api_key' " +
            "LEFT JOIN llm_model_props mb ON mb.model_config_id = m.id AND mb.prop_key = 'base_url' ";

    @Select(SELECT_COLS + FROM_JOIN)
    List<ModelConfig> findAll();

    @Select(SELECT_COLS + FROM_JOIN + "WHERE m.enabled = 1 ORDER BY m.priority ASC")
    List<ModelConfig> findAllEnabled();

    @Select(SELECT_COLS + FROM_JOIN + "WHERE m.id = #{id}")
    ModelConfig findById(@Param("id") Long id);

    @Select("<script>" + SELECT_COLS + FROM_JOIN + "WHERE m.id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<ModelConfig> findByIds(@Param("ids") List<Long> ids);

    @Select(SELECT_COLS + FROM_JOIN + "WHERE m.enabled = 1 AND m.model_type = #{modelType} ORDER BY m.priority ASC")
    List<ModelConfig> findAllEnabledByType(@Param("modelType") String modelType);

    /**
     * 新增模型配置（绑定到已存在的提供商；提供商不存在则不插入）。
     * api_key / base_url 属提供商级配置，由管理面或迁移脚本维护。
     */
    @Insert("<script>" +
            "INSERT INTO llm_model_config (provider_config_id, model_name, display_name, model_type, " +
            "max_tokens, enabled, is_default, priority, description, created_at) " +
            "SELECT id, #{model}, #{model}, #{modelType}, 4096, #{enabled}, 0, #{priority}, '', NOW() " +
            "FROM llm_provider_config WHERE provider_name = #{provider} " +
            "ON DUPLICATE KEY UPDATE model_type = VALUES(model_type), enabled = VALUES(enabled), " +
            "priority = VALUES(priority), display_name = VALUES(display_name)</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ModelConfig m);

    /**
     * 更新模型配置：模型名 / 类型 / 优先级 / 启用状态；提供商改名时同步
     * 更新 provider_config_id（提供商不存在则保留原值，避免外键异常）。
     */
    @Update("<script>" +
            "UPDATE llm_model_config m SET " +
            "provider_config_id = COALESCE((SELECT id FROM llm_provider_config WHERE provider_name = #{provider}), " +
            "                             m.provider_config_id), " +
            "model_name = #{model}, model_type = #{modelType}, priority = #{priority}, " +
            "enabled = #{enabled}, updated_at = NOW() WHERE m.id = #{id}</script>")
    int update(ModelConfig m);

    /** 删除模型配置（llm_model_props 由外键级联删除）。 */
    @Delete("DELETE FROM llm_model_config WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
