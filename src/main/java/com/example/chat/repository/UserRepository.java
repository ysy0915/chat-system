package com.example.chat.repository;

import com.example.chat.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface UserRepository {
    @Select("SELECT id, email, password_hash AS passwordHash, name, nickname, role, created_at AS createdAt FROM users WHERE email = #{email}")
    User findByEmail(String email);

    @Select("SELECT id, email, password_hash AS passwordHash, name, nickname, role, created_at AS createdAt FROM users WHERE name = #{name}")
    User findByName(String name);

    @Select("SELECT id, email, password_hash AS passwordHash, name, nickname, role, created_at AS createdAt FROM users WHERE id = #{id}")
    User findById(Long id);

    @Insert("INSERT INTO users (email, password_hash, name, nickname, role, created_at) VALUES (#{email}, #{passwordHash}, #{name}, #{nickname}, #{role}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User u);

    @Update("UPDATE users SET nickname = #{nickname}, name = #{name}, email = #{email} WHERE id = #{id}")
    int updateProfile(User u);
}
