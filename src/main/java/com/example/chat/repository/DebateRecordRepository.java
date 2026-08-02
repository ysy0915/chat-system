package com.example.chat.repository;

import com.example.chat.entity.DebateRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DebateRecordRepository {

    @Insert("INSERT INTO debate_records (user_id, user_name, question, status, created_at) VALUES (#{userId}, #{userName}, #{question}, #{status}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DebateRecord record);

    @Update("UPDATE debate_records SET final_answer = #{finalAnswer}, status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateAnswer(DebateRecord record);

    @Select("SELECT * FROM debate_records WHERE id = #{id}")
    DebateRecord findById(Long id);

    @Select("SELECT * FROM debate_records ORDER BY created_at DESC LIMIT 200")
    List<DebateRecord> findAll();

    @Select("SELECT * FROM debate_records WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT 100")
    List<DebateRecord> findByUserId(Long userId);
}
