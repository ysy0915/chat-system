package com.example.chat.repository;

import com.example.chat.entity.TreeHoleMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TreeHoleRepository {

    @Insert("INSERT INTO tree_hole_messages (req_id, user_id, question, status, mood, created_at) " +
            "VALUES (#{reqId}, #{userId}, #{question}, #{status}, #{mood}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TreeHoleMessage m);

    @Update("UPDATE tree_hole_messages SET answer_json = #{answerJson}, status = #{status}, updated_at = NOW() WHERE req_id = #{reqId}")
    int updateByReqId(TreeHoleMessage m);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, " +
            "status, mood, created_at AS createdAt FROM tree_hole_messages WHERE req_id = #{reqId}")
    TreeHoleMessage findByReqId(String reqId);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, " +
            "status, mood, created_at AS createdAt FROM tree_hole_messages " +
            "WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT 50")
    List<TreeHoleMessage> findByUserId(Long userId);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, " +
            "status, mood, created_at AS createdAt FROM tree_hole_messages " +
            "WHERE user_id = #{userId} AND answer_json IS NOT NULL AND answer_json != '' " +
            "AND created_at >= TIMESTAMPADD(MINUTE, -10, NOW()) ORDER BY created_at ASC")
    List<TreeHoleMessage> findRecentByUserId(Long userId);
}
