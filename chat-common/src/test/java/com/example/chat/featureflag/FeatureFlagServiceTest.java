package com.example.chat.featureflag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeatureFlagServiceTest {

    @Test
    @DisplayName("FlagConfig 内部类测试")
    void testFlagConfigFields() {
        FeatureFlagService.FlagConfig config = new FeatureFlagService.FlagConfig();
        assertFalse(config.enabled);
        assertEquals(0, config.percentage);
        assertNotNull(config.whitelist);
        assertNotNull(config.blacklist);
        assertNotNull(config.environments);
    }

    @Test
    @DisplayName("FlagConfig isEnvironmentEnabled 空列表返回 true")
    void testFlagConfigEmptyEnvironments() {
        FeatureFlagService.FlagConfig config = new FeatureFlagService.FlagConfig();
        assertTrue(config.isEnvironmentEnabled("any"));
    }

    @Test
    @DisplayName("FlagConfig isEnvironmentEnabled 匹配返回 true")
    void testFlagConfigMatchingEnv() {
        FeatureFlagService.FlagConfig config = new FeatureFlagService.FlagConfig();
        config.environments.add("dev");
        assertTrue(config.isEnvironmentEnabled("dev"));
    }

    @Test
    @DisplayName("FlagConfig isEnvironmentEnabled 不匹配返回 false")
    void testFlagConfigNonMatchingEnv() {
        FeatureFlagService.FlagConfig config = new FeatureFlagService.FlagConfig();
        config.environments.add("prod");
        assertFalse(config.isEnvironmentEnabled("dev"));
    }
}
