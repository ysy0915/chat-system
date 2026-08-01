package com.example.chat.service;

import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import org.springframework.stereotype.Service;

@Service
public class MessageService {
    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public Message findByReqId(String reqId) {
        return messageRepository.findByReqId(reqId);
    }

    public Message save(Message m) {
        if (m.id == null) {
            messageRepository.insert(m);
            return m;
        } else {
            messageRepository.updateByReqId(m);
            return m;
        }
    }
}
