package com.example.chat.agent.tool;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <h2>工具注册表 DB 仓储（tool_registry 表）</h2>
 *
 * <p>[B档] 工具平台化：工具元数据的持久化层。管理面对工具启用/描述/参数 Schema/范围的
 * 声明写入本表；启动时由 {@link ToolRegistry#applyDbOverrides()} 合并覆盖代码默认声明。</p>
 *
 * <p>表结构见 <code>docs/sql/tool_registry_schema.sql</code>。
 * 表未建时所有查询抛异常，{@link ToolRegistry} 会容错降级（按代码默认注册继续）。</p>
 */
@Mapper
public interface ToolRegistryRepository {

    @Select("SELECT tool_name AS toolName, description, parameters, enabled, scope, source " +
            "FROM tool_registry ORDER BY id")
    List<ToolDefinition> findAll();

    @Select("SELECT tool_name AS toolName, description, parameters, enabled, scope, source " +
            "FROM tool_registry WHERE tool_name = #{toolName}")
    ToolDefinition findByName(@Param("toolName") String toolName);

    /**
     * 幂等写入声明（存在则更新）。
     */
    @Insert("INSERT INTO tool_registry (tool_name, description, parameters, enabled, scope) " +
            "VALUES (#{toolName}, #{description}, #{parameters}, #{enabled}, #{scope}) " +
            "ON DUPLICATE KEY UPDATE description = VALUES(description), " +
            "parameters = VALUES(parameters), enabled = VALUES(enabled), scope = VALUES(scope), " +
            "source = 'DB', updated_at = NOW()")
    int upsert(ToolDefinition def);

    @Delete("DELETE FROM tool_registry WHERE tool_name = #{toolName}")
    int deleteByName(@Param("toolName") String toolName);
}
