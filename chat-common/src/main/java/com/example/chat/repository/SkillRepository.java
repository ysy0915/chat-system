package com.example.chat.repository;

import com.example.chat.entity.Skill;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 技能注册中心 Mapper（Step 3）—— skill_registry。
 * 注解式 MyBatis，无 XML。
 */
@Mapper
public interface SkillRepository {

    String COLS = "id, name, description, language, code, trigger_prompt AS triggerPrompt, "
            + "source_trace AS sourceTrace, usage_count AS usageCount, status, "
            + "created_at AS createdAt, updated_at AS updatedAt";

    @Select("SELECT " + COLS + " FROM skill_registry WHERE status = 1 ORDER BY usage_count DESC")
    List<Skill> findAllEnabled();

    @Select("SELECT " + COLS + " FROM skill_registry WHERE id = #{id}")
    Skill findById(@Param("id") Long id);

    @Select("SELECT " + COLS + " FROM skill_registry WHERE name = #{name}")
    Skill findByName(@Param("name") String name);

    @Insert("INSERT INTO skill_registry (name, description, language, code, trigger_prompt, source_trace, status, created_at) "
            + "VALUES (#{name}, #{description}, #{language}, #{code}, #{triggerPrompt}, #{sourceTrace}, 1, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Skill s);

    @Update("UPDATE skill_registry SET usage_count = usage_count + 1, updated_at = NOW() WHERE id = #{id}")
    int incrementUsage(@Param("id") Long id);

    @Update("UPDATE skill_registry SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);
}
