package com.example.chat.service;

import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(messageRepository);
    }

    @Test
    @DisplayName("findByReqId 返回结果")
    void testFindByReqId() {
        Message mockMessage = new Message();
        mockMessage.setReqId("req-001");
        mockMessage.setQuestion("Hello");

        when(messageRepository.findByReqId("req-001")).thenReturn(mockMessage);

        Message result = messageService.findByReqId("req-001");
        assertNotNull(result);
        assertEquals("req-001", result.getReqId());
        assertEquals("Hello", result.getQuestion());
        verify(messageRepository).findByReqId("req-001");
    }

    @Test
    @DisplayName("findByReqId 不存在返回 null")
    void testFindByReqId_notFound() {
        when(messageRepository.findByReqId("nonexistent")).thenReturn(null);

        Message result = messageService.findByReqId("nonexistent");
        assertNull(result);
    }

    @Test
    @DisplayName("save 新消息 (id=null) 调用 insert")
    void testSave_newMessage() {
        Message msg = new Message();
        msg.setQuestion("new message");
        // id 默认为 null

        when(messageRepository.insert(any(Message.class))).thenReturn(1);

        Message result = messageService.save(msg);
        assertNotNull(result);
        assertEquals("new message", result.getQuestion());
        verify(messageRepository).insert(msg);
        verify(messageRepository, never()).updateByReqId(any());
    }

    @Test
    @DisplayName("save 已有消息 (id!=null) 调用 updateByReqId")
    void testSave_existingMessage() {
        Message msg = new Message();
        msg.setId(1L);
        msg.setReqId("req-001");
        msg.setQuestion("updated content");

        when(messageRepository.updateByReqId(msg)).thenReturn(1);

        Message result = messageService.save(msg);
        assertNotNull(result);
        assertEquals("updated content", result.getQuestion());
        verify(messageRepository).updateByReqId(msg);
        verify(messageRepository, never()).insert(any());
    }

    @Test
    @DisplayName("类存在验证")
    void testClassExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.service.MessageService");
        });
    }
}
