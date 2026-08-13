package com.example.chat.repository;

import com.example.chat.entity.UserProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户画像表 Mapper（L3）—— user_profiles。
 * 注解式 MyBatis，无 XML。
 */
@Mapper
public interface UserProfileRepository {

    String COLS = "id, user_id AS userId, scene, scene_desc AS sceneDesc, "
            + "emotions_json AS emotionsJson, preferences_json AS preferencesJson, "
            + "contexts_json AS contextsJson, source_count AS sourceCount, "
            + "updated_at AS updatedAt, created_at AS createdAt";

    @Select("SELECT " + COLS + " FROM user_profiles WHERE user_id = #{userId} AND scene = #{scene}")
    UserProfile findByUserIdAndScene(@Param("userId") Long userId, @Param("scene") String scene);

    @Select("SELECT " + COLS + " FROM user_profiles WHERE user_id = #{userId} ORDER BY updated_at DESC")
    List<UserProfile> findByUserId(@Param("userId") Long userId);

    @Insert("INSERT INTO user_profiles (user_id, scene, scene_desc, emotions_json, preferences_json, contexts_json, source_count, created_at) "
            + "VALUES (#{userId}, #{scene}, #{sceneDesc}, #{emotionsJson}, #{preferencesJson}, #{contextsJson}, #{sourceCount}, NOW()) "
            + "ON DUPLICATE KEY UPDATE scene_desc = VALUES(scene_desc), emotions_json = VALUES(emotions_json), "
            + "preferences_json = VALUES(preferences_json), contexts_json = VALUES(contexts_json), "
            + "source_count = source_count + 1, updated_at = NOW()")
    int upsert(UserProfile p);

    @Update("UPDATE user_profiles SET source_count = source_count + 1, updated_at = NOW() WHERE id = #{id}")
    int touch(@Param("id") Long id);

    @Update("UPDATE user_profiles SET usage_count = usage_count + 1 WHERE id = #{id}")
    int incrementUsage(@Param("id") Long id);

    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("INSERT INTO user_profiles (user_id, scene, scene_desc, emotions_json, preferences_json, contexts_json, source_count, created_at) "
            + "VALUES (#{userId}, #{scene}, #{sceneDesc}, #{emotionsJson}, #{preferencesJson}, #{contextsJson}, #{sourceCount}, NOW())")
    int insert(UserProfile p);
}
