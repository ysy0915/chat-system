package com.example.chat.langchain4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonalChatAssistantTest {

    @Test
    void shouldBeInterface() {
        assertTrue(PersonalChatAssistant.class.isInterface(),
                "PersonalChatAssistant should be an interface");
    }
}
