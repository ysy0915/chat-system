package com.example.chat.repository;

import com.example.chat.entity.UserRegistration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserRegistrationRepository {
    @Insert("INSERT INTO user_registrations (user_id, email, username, nickname, registered_at) VALUES (#{userId}, #{email}, #{username}, #{nickname}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserRegistration r);

    @Select("SELECT id, user_id AS userId, email, username, nickname, registered_at AS registeredAt FROM user_registrations WHERE user_id = #{userId} ORDER BY id DESC LIMIT 1")
    UserRegistration findByUserId(Long userId);

    @Update("UPDATE user_registrations SET nickname = #{nickname} WHERE user_id = #{userId}")
    int updateNicknameByUserId(Long userId, String nickname);
}
