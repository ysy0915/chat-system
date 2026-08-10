package com.example.chat.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GraphRepositoryServiceTest {

    @Test
    void shouldInstantiate() {
        GraphRepositoryService service = new GraphRepositoryService();
        assertNotNull(service);
    }
}
