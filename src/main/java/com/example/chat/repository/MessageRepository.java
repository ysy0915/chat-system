package com.example.chat.repository;

import com.example.chat.entity.Message;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MessageRepository {
    @org.apache.ibatis.annotations.Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, status, provider, model, created_at AS createdAt FROM messages WHERE req_id = #{reqId}")
    Message findByReqId(String reqId);

    @org.apache.ibatis.annotations.Insert("INSERT INTO messages (req_id, user_id, question, status, created_at) VALUES (#{reqId}, #{userId}, #{question}, #{status}, NOW())")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Message m);

    @org.apache.ibatis.annotations.Update("UPDATE messages SET answer_json = #{answerJson}, status = #{status}, updated_at = NOW() WHERE req_id = #{reqId}")
    int updateByReqId(Message m);

    @org.apache.ibatis.annotations.Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, status, provider, model, created_at AS createdAt FROM messages WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT 200")
    java.util.List<Message> findByUserId(Long userId);

    @org.apache.ibatis.annotations.Select("SELECT id, req_id AS reqId, user_id AS userId, question, answer_json AS answerJson, status, provider, model, created_at AS createdAt FROM messages ORDER BY created_at DESC LIMIT 200")
    java.util.List<Message> findAllMessages();

    @org.apache.ibatis.annotations.Select("SELECT id, question FROM messages WHERE answer_json IS NOT NULL AND answer_json != '' ORDER BY created_at DESC LIMIT 150")
    java.util.List<Message> findQuestionsOnly();

    @org.apache.ibatis.annotations.Select("SELECT id, question FROM messages WHERE answer_json IS NOT NULL AND answer_json != '' AND (question LIKE CONCAT('%', #{keyword}, '%') OR answer_json LIKE CONCAT('%', #{keyword}, '%')) ORDER BY created_at DESC LIMIT 30")
    java.util.List<Message> searchQuestions(String keyword);

    @org.apache.ibatis.annotations.Select("SELECT answer_json AS answerJson FROM messages WHERE id = #{id}")
    Message findAnswerById(Long id);
}
