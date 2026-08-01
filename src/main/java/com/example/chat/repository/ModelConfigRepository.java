package com.example.chat.repository;

import com.example.chat.entity.ModelConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ModelConfigRepository {
    @org.apache.ibatis.annotations.Select("SELECT id, provider, model, api_key_encrypted AS apiKeyEncrypted, meta AS metaJson, priority, enabled, created_at AS createdAt FROM model_configs")
    List<ModelConfig> findAll();

    @org.apache.ibatis.annotations.Select("SELECT id, provider, model, api_key_encrypted AS apiKeyEncrypted, meta AS metaJson, priority, enabled, created_at AS createdAt FROM model_configs WHERE enabled = 1 ORDER BY priority ASC")
    List<ModelConfig> findAllEnabled();

    @org.apache.ibatis.annotations.Select("SELECT id, provider, model, api_key_encrypted AS apiKeyEncrypted, meta AS metaJson, priority, enabled, created_at AS createdAt FROM model_configs WHERE id = #{id}")
    ModelConfig findById(Long id);

    @org.apache.ibatis.annotations.Select("<script>SELECT id, provider, model, api_key_encrypted AS apiKeyEncrypted, meta AS metaJson, priority, enabled, created_at AS createdAt FROM model_configs WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<ModelConfig> findByIds(@org.apache.ibatis.annotations.Param("ids") List<Long> ids);

    @org.apache.ibatis.annotations.Insert("INSERT INTO model_configs (provider, model, api_key_encrypted, meta, priority, enabled, created_at) VALUES (#{provider}, #{model}, #{apiKeyEncrypted}, #{metaJson}, #{priority}, #{enabled}, NOW())")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ModelConfig m);

    @org.apache.ibatis.annotations.Update("UPDATE model_configs SET provider=#{provider}, model=#{model}, api_key_encrypted=#{apiKeyEncrypted}, meta=#{metaJson}, priority=#{priority}, enabled=#{enabled}, updated_at=NOW() WHERE id=#{id}")
    int update(ModelConfig m);

    @org.apache.ibatis.annotations.Delete("DELETE FROM model_configs WHERE id = #{id}")
    int deleteById(Long id);
}
