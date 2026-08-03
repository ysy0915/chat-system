package com.example.chat.repository;

import com.example.chat.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MessageRepository {
    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, status, provider, model, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE req_id = #{reqId}")
    Message findByReqId(String reqId);

    @Insert("INSERT INTO messages (req_id, user_id, question, status, is_private, created_at) VALUES (#{reqId}, #{userId}, #{question}, #{status}, #{isPrivate}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Message m);

    @Update("UPDATE messages SET answer_json = #{answerJson}, status = #{status}, updated_at = NOW() WHERE req_id = #{reqId}")
    int updateByReqId(Message m);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, status, provider, model, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT 200")
    java.util.List<Message> findByUserId(Long userId);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, status, provider, model, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE (is_private IS NULL OR is_private = 0) ORDER BY created_at DESC LIMIT 200")
    java.util.List<Message> findAllMessages();

    @Select("SELECT MIN(id) AS id, question FROM messages WHERE (is_private IS NULL OR is_private = 0) AND answer_json IS NOT NULL AND answer_json != '' GROUP BY question ORDER BY MAX(created_at) DESC LIMIT 150")
    java.util.List<Message> findQuestionsOnly();

    @Select("SELECT id, question FROM messages WHERE (is_private IS NULL OR is_private = 0) AND answer_json IS NOT NULL AND answer_json != '' AND (question LIKE CONCAT('%', #{keyword}, '%') OR answer_json LIKE CONCAT('%', #{keyword}, '%')) ORDER BY created_at DESC LIMIT 30")
    java.util.List<Message> searchQuestions(String keyword);

    @Select("SELECT answer_json AS answerJson FROM messages WHERE id = #{id}")
    Message findAnswerById(Long id);

    @Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, status, provider, model, is_private AS isPrivate, created_at AS createdAt FROM messages WHERE user_id = #{userId} AND is_private = 1 AND answer_json IS NOT NULL AND answer_json != '' AND created_at >= TIMESTAMPADD(MINUTE, -10, NOW()) ORDER BY created_at ASC")
    java.util.List<Message> findRecentByUserId(Long userId);
}
