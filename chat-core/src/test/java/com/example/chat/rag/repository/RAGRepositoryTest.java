package com.example.chat.rag.repository;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RAGRepositoryTest {

    @Test
    void shouldHaveMapperAnnotation() {
        assertTrue(
            RAGRepository.class.isAnnotationPresent(org.apache.ibatis.annotations.Mapper.class),
            "RAGRepository should have @Mapper annotation"
        );
    }

    @Test
    void shouldBeInterface() {
        assertTrue(RAGRepository.class.isInterface(), "RAGRepository should be an interface");
    }

    @Test
    void shouldHaveFindAllKnowledgeBasesMethod() throws NoSuchMethodException {
        assertNotNull(RAGRepository.class.getMethod("findAllKnowledgeBases"));
    }

    @Test
    void shouldHaveInsertDocumentMethod() throws NoSuchMethodException {
        assertNotNull(RAGRepository.class.getMethod("insertDocument",
            com.example.chat.rag.entity.KnowledgeDocument.class));
    }
}
