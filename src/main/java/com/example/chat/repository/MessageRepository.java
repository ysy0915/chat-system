package com.example.chat.repository;

import com.example.chat.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MessageRepository {
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, summary, answer_json AS answerJson, status, provider, model, tokens, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE req_id = #{reqId}")
    Message findByReqId(String reqId);

    @Update("UPDATE messages SET summary = #{summary} WHERE id = #{id}")
    int updateSummary(@Param("id") Long id, @Param("summary") String summary);

    /** 更新回答、模型、tokens、状态 */
    @Update("UPDATE messages SET answer_json = #{answerJson}, status = #{status}, provider = #{provider}, model = #{model}, tokens = #{tokens}, updated_at = NOW() WHERE req_id = #{reqId}")
    int updateAnswerByReqId(@Param("reqId") String reqId, @Param("answerJson") String answerJson, @Param("status") String status, @Param("provider") String provider, @Param("model") String model, @Param("tokens") Integer tokens);

    @Insert("INSERT INTO messages (req_id, user_id, question, status, provider, model, tokens, is_private, created_at) VALUES (#{reqId}, #{userId}, #{question}, #{status}, #{provider}, #{model}, #{tokens}, #{isPrivate}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Message m);

    @Update("UPDATE messages SET answer_json = #{answerJson}, status = #{status}, updated_at = NOW() WHERE req_id = #{reqId}")
    int updateByReqId(Message m);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, summary, answer_json AS answerJson, status, provider, model, tokens, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT 200")
    java.util.List<Message> findByUserId(Long userId);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, summary, answer_json AS answerJson, status, provider, model, tokens, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE (is_private IS NULL OR is_private = 0) ORDER BY created_at DESC LIMIT 200")
    java.util.List<Message> findAllMessages();

    @Select("SELECT MIN(id) AS id, question FROM messages WHERE (is_private IS NULL OR is_private = 0) AND answer_json IS NOT NULL AND answer_json != '' GROUP BY question ORDER BY MAX(created_at) DESC LIMIT 150")
    java.util.List<Message> findQuestionsOnly();

    @Select("SELECT id, question FROM messages WHERE (is_private IS NULL OR is_private = 0) AND answer_json IS NOT NULL AND answer_json != '' AND (question LIKE CONCAT('%', #{keyword}, '%') OR answer_json LIKE CONCAT('%', #{keyword}, '%')) ORDER BY created_at DESC LIMIT 30")
    java.util.List<Message> searchQuestions(String keyword);

    @Select("SELECT answer_json AS answerJson FROM messages WHERE id = #{id}")
    Message findAnswerById(Long id);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, summary, answer_json AS answerJson, status, provider, model, tokens, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE user_id = #{userId} AND is_private = 1 AND answer_json IS NOT NULL AND answer_json != '' AND created_at >= TIMESTAMPADD(MINUTE, -10, NOW()) ORDER BY created_at ASC")
    java.util.List<Message> findRecentByUserId(Long userId);

    /** 个人对话：最近 N 条（用于页面初始化加载） */
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, summary, answer_json AS answerJson, status, provider, model, tokens, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE user_id = #{userId} AND is_private = 1 AND answer_json IS NOT NULL AND answer_json != '' ORDER BY created_at DESC LIMIT #{limit}")
    java.util.List<Message> findRecentPrivateByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /** 个人对话：搜索历史问题（分页） */
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, summary, answer_json AS answerJson, status, provider, model, tokens, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE user_id = #{userId} AND is_private = 1 AND answer_json IS NOT NULL AND answer_json != '' AND (question LIKE CONCAT('%', #{keyword}, '%') OR summary LIKE CONCAT('%', #{keyword}, '%')) ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    java.util.List<Message> searchPrivateMessages(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("offset") int offset, @Param("limit") int limit);

    /** 个人对话：根据某条记录的 createdAt 获取前后共 5 条上下文 */
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, summary, answer_json AS answerJson, status, provider, model, tokens, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE user_id = #{userId} AND is_private = 1 AND answer_json IS NOT NULL AND answer_json != '' AND created_at >= (SELECT created_at FROM messages WHERE id = #{msgId}) ORDER BY created_at ASC LIMIT 5")
    java.util.List<Message> findContextAround(@Param("userId") Long userId, @Param("msgId") Long msgId);

    /** 个人对话：搜索结果总数 */
    @Select("SELECT COUNT(*) FROM messages WHERE user_id = #{userId} AND is_private = 1 AND answer_json IS NOT NULL AND answer_json != '' AND (question LIKE CONCAT('%', #{keyword}, '%') OR summary LIKE CONCAT('%', #{keyword}, '%'))")
    int countSearchPrivateMessages(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 树洞：最近 N 条 */
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, status, created_at AS createdAt FROM tree_hole_messages WHERE user_id = #{userId} AND answer_json IS NOT NULL AND answer_json != '' ORDER BY created_at DESC LIMIT #{limit}")
    java.util.List<com.example.chat.entity.TreeHoleMessage> findRecentTreeHoleByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /** 树洞：搜索历史问题 */
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, status, created_at AS createdAt FROM tree_hole_messages WHERE user_id = #{userId} AND answer_json IS NOT NULL AND answer_json != '' AND question LIKE CONCAT('%', #{keyword}, '%') ORDER BY created_at DESC LIMIT 30")
    java.util.List<com.example.chat.entity.TreeHoleMessage> searchTreeHoleMessages(@Param("userId") Long userId, @Param("keyword") String keyword);
}
