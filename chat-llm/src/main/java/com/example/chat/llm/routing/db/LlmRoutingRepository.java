package com.example.chat.llm.routing.db;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <h2>LLM 路由 DB 仓储</h2>
 *
 * <p>模型管理面的数据访问层，读写 <code>llm_provider_config</code> /
 * <code>llm_provider_props</code> / <code>llm_model_config</code> 三张表。</p>
 *
 * <p>表结构定义见 <code>docs/sql/llm_routing_schema.sql</code>。</p>
 */
@Mapper
public interface LlmRoutingRepository {

    // ──────────── 提供商 ────────────

    @Select("""
            SELECT id, provider_name AS providerName, base_url AS baseUrl,
                   auth_type AS authType, invoke_type AS invokeType,
                   enabled, is_default AS isDefault, priority, description
            FROM llm_provider_config
            ORDER BY priority, id
            """)
    List<LlmProviderRow> listProviders();

    @Select("""
            SELECT id, provider_name AS providerName, base_url AS baseUrl,
                   auth_type AS authType, invoke_type AS invokeType,
                   enabled, is_default AS isDefault, priority, description
            FROM llm_provider_config
            WHERE id = #{id}
            """)
    LlmProviderRow findProviderById(@Param("id") Long id);

    @Select("""
            SELECT id, provider_name AS providerName, base_url AS baseUrl,
                   auth_type AS authType, invoke_type AS invokeType,
                   enabled, is_default AS isDefault, priority, description
            FROM llm_provider_config
            WHERE provider_name = #{name}
            """)
    LlmProviderRow findProviderByName(@Param("name") String name);

    @Insert("""
            INSERT INTO llm_provider_config
                (provider_name, base_url, auth_type, invoke_type, enabled, is_default, priority, description)
            VALUES
                (#{providerName}, #{baseUrl}, #{authType}, #{invokeType}, #{enabled}, #{isDefault}, #{priority}, #{description})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertProvider(LlmProviderRow row);

    @Update("""
            UPDATE llm_provider_config
            SET provider_name = #{providerName}, base_url = #{baseUrl},
                auth_type = #{authType}, invoke_type = #{invokeType},
                enabled = #{enabled}, is_default = #{isDefault},
                priority = #{priority}, description = #{description}
            WHERE id = #{id}
            """)
    int updateProvider(LlmProviderRow row);

    @Delete("DELETE FROM llm_provider_config WHERE id = #{id}")
    int deleteProvider(@Param("id") Long id);

    // ──────────── 提供商 KV 属性 ────────────

    @Select("""
            SELECT prop_key AS propKey, prop_value AS propValue, prop_type AS propType
            FROM llm_provider_props
            WHERE provider_config_id = #{providerId}
            """)
    List<Map<String, Object>> listProps(@Param("providerId") Long providerId);

    @Insert("""
            INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description)
            VALUES (#{providerId}, #{propKey}, #{propValue}, #{propType}, #{desc})
            """)
    int insertProp(@Param("providerId") Long providerId,
                   @Param("propKey") String propKey,
                   @Param("propValue") String propValue,
                   @Param("propType") String propType,
                   @Param("desc") String desc);

    @Delete("DELETE FROM llm_provider_props WHERE provider_config_id = #{providerId}")
    int deleteProps(@Param("providerId") Long providerId);

    // ──────────── 模型 ────────────

    @Select("""
            SELECT id, provider_config_id AS providerId, model_name AS modelName,
                   display_name AS displayName, model_type AS modelType, max_tokens AS maxTokens,
                   enabled, is_default AS isDefault, priority, description
            FROM llm_model_config
            WHERE provider_config_id = #{providerId}
            ORDER BY priority, id
            """)
    List<LlmModelRow> listModels(@Param("providerId") Long providerId);

    @Insert("""
            INSERT INTO llm_model_config
                (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
            VALUES
                (#{providerId}, #{modelName}, #{displayName}, #{modelType}, #{maxTokens}, #{enabled}, #{isDefault}, #{priority}, #{description})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertModel(LlmModelRow row);

    @Delete("DELETE FROM llm_model_config WHERE provider_config_id = #{providerId}")
    int deleteModels(@Param("providerId") Long providerId);
}
