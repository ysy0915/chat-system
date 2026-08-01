package com.example.chat.repository;

import com.example.chat.entity.UserRegistration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface UserRegistrationRepository {
    @Insert("INSERT INTO user_registrations (user_id, email, username, registered_at) VALUES (#{userId}, #{email}, #{username}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserRegistration r);
}
