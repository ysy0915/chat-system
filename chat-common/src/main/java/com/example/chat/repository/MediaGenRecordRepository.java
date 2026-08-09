package com.example.chat.repository;

import com.example.chat.entity.MediaGenRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MediaGenRecordRepository {

    @Insert("INSERT INTO media_gen_records (user_id, prompt, media_type, model, media_url, glb_url, obj_url, preview_url, status, error_msg) VALUES (#{r.userId}, #{r.prompt}, #{r.mediaType}, #{r.model}, #{r.mediaUrl}, #{r.glbUrl}, #{r.objUrl}, #{r.previewUrl}, #{r.status}, #{r.errorMsg})")
    @Options(useGeneratedKeys = true, keyProperty = "r.id")
    int insert(@Param("r") MediaGenRecord r);

    @Update("UPDATE media_gen_records SET status='done', media_url=#{mediaUrl}, glb_url=#{glbUrl}, obj_url=#{objUrl}, preview_url=#{previewUrl} WHERE id=#{id}")
    int updateToDone(@Param("id") Long id, @Param("mediaUrl") String mediaUrl,
                     @Param("glbUrl") String glbUrl, @Param("objUrl") String objUrl,
                     @Param("previewUrl") String previewUrl);

    @Update("UPDATE media_gen_records SET status='error', error_msg=#{errorMsg} WHERE id=#{id}")
    int updateToError(@Param("id") Long id, @Param("errorMsg") String errorMsg);

    @Select("SELECT id, user_id AS userId, prompt, media_type AS mediaType, model, media_url AS mediaUrl, glb_url AS glbUrl, obj_url AS objUrl, preview_url AS previewUrl, status, error_msg AS errorMsg, created_at AS createdAt FROM media_gen_records WHERE id = #{id}")
    @Results({
            @Result(property = "createdAt", column = "created_at", javaType = java.time.Instant.class)
    })
    MediaGenRecord findById(@Param("id") Long id);

    @Select("SELECT id, user_id AS userId, prompt, media_type AS mediaType, model, media_url AS mediaUrl, glb_url AS glbUrl, obj_url AS objUrl, preview_url AS previewUrl, status, error_msg AS errorMsg, created_at AS createdAt FROM media_gen_records WHERE user_id = #{userId} AND status = 'running' ORDER BY created_at DESC")
    @Results({
            @Result(property = "createdAt", column = "created_at", javaType = java.time.Instant.class)
    })
    List<MediaGenRecord> findRunningByUserId(@Param("userId") Long userId);

    @Select("SELECT id, user_id AS userId, prompt, media_type AS mediaType, model, media_url AS mediaUrl, glb_url AS glbUrl, obj_url AS objUrl, preview_url AS previewUrl, status, error_msg AS errorMsg, created_at AS createdAt FROM media_gen_records WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    @Results({
            @Result(property = "createdAt", column = "created_at", javaType = java.time.Instant.class)
    })
    List<MediaGenRecord> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT id, user_id AS userId, prompt, media_type AS mediaType, model, media_url AS mediaUrl, glb_url AS glbUrl, obj_url AS objUrl, preview_url AS previewUrl, status, error_msg AS errorMsg, created_at AS createdAt FROM media_gen_records WHERE user_id = #{userId} AND media_type = #{mediaType} ORDER BY created_at DESC LIMIT #{limit}")
    @Results({
            @Result(property = "createdAt", column = "created_at", javaType = java.time.Instant.class)
    })
    List<MediaGenRecord> findByUserIdAndType(@Param("userId") Long userId, @Param("mediaType") String mediaType, @Param("limit") int limit);
}
