package com.example.chat.repository;

import com.example.chat.entity.TreeHoleMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TreeHoleRepository {

    @Insert("INSERT INTO tree_hole_messages (req_id, user_id, question, status, mood, provider, model, tokens, created_at) " +
            "VALUES (#{reqId}, #{userId}, #{question}, #{status}, #{mood}, #{provider}, #{model}, #{tokens}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TreeHoleMessage m);

    @Update("UPDATE tree_hole_messages SET answer_json = #{answerJson}, status = #{status}, provider = #{provider}, model = #{model}, tokens = #{tokens}, updated_at = NOW() WHERE req_id = #{reqId}")
    int updateByReqId(TreeHoleMessage m);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, " +
            "status, mood, provider, model, tokens, created_at AS createdAt FROM tree_hole_messages WHERE req_id = #{reqId}")
    TreeHoleMessage findByReqId(String reqId);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, " +
            "status, mood, provider, model, tokens, created_at AS createdAt FROM tree_hole_messages " +
            "WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT 50")
    List<TreeHoleMessage> findByUserId(Long userId);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, " +
            "status, mood, provider, model, tokens, created_at AS createdAt FROM tree_hole_messages " +
            "WHERE user_id = #{userId} AND answer_json IS NOT NULL AND answer_json != '' " +
            "AND created_at >= TIMESTAMPADD(MINUTE, -10, NOW()) ORDER BY created_at ASC")
    List<TreeHoleMessage> findRecentByUserId(Long userId);

    /** 最近 N 条（页面初始化） */
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, " +
            "status, mood, provider, model, tokens, created_at AS createdAt FROM tree_hole_messages " +
            "WHERE user_id = #{userId} AND answer_json IS NOT NULL AND answer_json != '' " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<TreeHoleMessage> findRecentNByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /** 搜索历史（分页） */
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, " +
            "status, mood, provider, model, tokens, created_at AS createdAt FROM tree_hole_messages " +
            "WHERE user_id = #{userId} AND answer_json IS NOT NULL AND answer_json != '' " +
            "AND question LIKE CONCAT('%', #{keyword}, '%') ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<TreeHoleMessage> searchByKeyword(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("offset") int offset, @Param("limit") int limit);

    /** 搜索结果总数 */
    @Select("SELECT COUNT(*) FROM tree_hole_messages WHERE user_id = #{userId} AND answer_json IS NOT NULL AND answer_json != '' AND question LIKE CONCAT('%', #{keyword}, '%')")
    int countSearchByKeyword(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 根据某条记录获取前后 5 条上下文 */
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, " +
            "status, mood, provider, model, tokens, created_at AS createdAt FROM tree_hole_messages " +
            "WHERE user_id = #{userId} AND answer_json IS NOT NULL AND answer_json != '' " +
            "AND created_at >= (SELECT created_at FROM tree_hole_messages WHERE id = #{msgId}) " +
            "ORDER BY created_at ASC LIMIT 5")
    List<TreeHoleMessage> findContextAround(@Param("userId") Long userId, @Param("msgId") Long msgId);
}
