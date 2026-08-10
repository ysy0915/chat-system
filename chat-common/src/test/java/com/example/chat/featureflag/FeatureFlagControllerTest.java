package com.example.chat.featureflag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeatureFlagControllerTest {

    @Test
    @DisplayName("构造函数 + auto-wired 字段注入")
    void testConstructor() {
        FeatureFlagController controller = new FeatureFlagController();
        // @Autowired 字段在测试中不注入，验证实例创建
        assertNotNull(controller);
    }
}
