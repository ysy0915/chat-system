package com.example.chat.service;

import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 个人资料业务逻辑（查看 / 修改）。
 *
 * <p>从 {@code ProfileController} 抽离，Controller 仅负责 JWT 提取与协议适配。</p>
 */
@Service
public class ProfileService {

    private final UserRepository userRepository;

    public ProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ResponseEntity<?> getProfile(Long userId) {
        User user = userRepository.findById(userId);
        if (user == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(ErrorCode.NOT_FOUND, "用户不存在"));
        }
        return ResponseEntity.ok(Map.of(
                "id", user.id,
                "name", user.name != null ? user.name : "",
                "nickname", user.nickname != null ? user.nickname : "",
                "email", user.email != null ? user.email : "",
                "role", user.role != null ? user.role : "",
                "createdAt", user.createdAt != null ? user.createdAt.toString() : ""
        ));
    }

    public ResponseEntity<?> updateProfile(Long userId, Map<String, String> body) {
        User user = userRepository.findById(userId);
        if (user == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(ErrorCode.NOT_FOUND, "用户不存在"));
        }

        String nickname = body.get("nickname");
        String name = body.get("name");

        if (nickname != null) user.nickname = nickname.trim();
        if (name != null && !name.trim().isBlank()) user.name = name.trim();

        userRepository.updateProfile(user);

        return ResponseEntity.ok(Map.of(
                "id", user.id,
                "name", user.name != null ? user.name : "",
                "nickname", user.nickname != null ? user.nickname : ""
        ));
    }
}
