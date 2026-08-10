package com.example.chat.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CoreClient 类存在性验证测试
 */
class CoreClientTest {

    @Test
    void classExists() {
        assertNotNull(CoreClient.class);
    }

    @Test
    void canBeInstantiatedWithRestTemplate() {
        CoreClient client = new CoreClient(new RestTemplate());
        assertNotNull(client);
    }

    @Test
    void canBeInstantiatedWithoutRestTemplate() {
        CoreClient client = new CoreClient(null);
        assertNotNull(client);
    }
}
