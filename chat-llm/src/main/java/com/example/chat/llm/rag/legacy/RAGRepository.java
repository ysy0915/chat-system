package com.example.chat.llm.rag.legacy;

import com.example.chat.llm.rag.legacy.KnowledgeBase;
import com.example.chat.llm.rag.legacy.KnowledgeDocument;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * RAG 知识库 MyBatis Mapper
 */
@Mapper
public interface RAGRepository {

    // ============ 知识库管理 ============

    @Select("SELECT * FROM rag_knowledge_bases ORDER BY id DESC")
    List<KnowledgeBase> findAllKnowledgeBases();

    @Select("SELECT * FROM rag_knowledge_bases WHERE id = #{id}")
    KnowledgeBase findKnowledgeBaseById(@Param("id") Long id);

    @Insert("INSERT INTO rag_knowledge_bases (name, description, created_at) " +
            "VALUES (#{name}, #{description}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertKnowledgeBase(KnowledgeBase kb);

    @Delete("DELETE FROM rag_knowledge_bases WHERE id = #{id}")
    int deleteKnowledgeBase(@Param("id") Long id);

    @Update("UPDATE rag_knowledge_bases SET document_count = #{docCount}, total_chunks = #{chunks} WHERE id = #{id}")
    int updateKnowledgeBaseStats(@Param("id") Long id, @Param("docCount") int docCount, @Param("chunks") long chunks);

    // ============ 文档管理 ============

    @Select("SELECT * FROM rag_documents WHERE knowledge_base_id = #{kbId} ORDER BY id DESC")
    List<KnowledgeDocument> findDocumentsByKbId(@Param("kbId") Long kbId);

    @Insert("INSERT INTO rag_documents (knowledge_base_id, file_name, source, chunk_count, file_size, status, created_at) " +
            "VALUES (#{knowledgeBaseId}, #{fileName}, #{source}, #{chunkCount}, #{fileSize}, #{status}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDocument(KnowledgeDocument doc);

    @Update("UPDATE rag_documents SET status = #{status}, error_message = #{errorMessage}, chunk_count = #{chunkCount} WHERE id = #{id}")
    int updateDocumentStatus(@Param("id") Long id, @Param("status") String status,
                             @Param("errorMessage") String errorMessage, @Param("chunkCount") int chunkCount);

    @Delete("DELETE FROM rag_documents WHERE id = #{id}")
    int deleteDocument(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM rag_documents WHERE knowledge_base_id = #{kbId}")
    int countDocumentsByKbId(@Param("kbId") Long kbId);

    @Select("SELECT COALESCE(SUM(chunk_count), 0) FROM rag_documents WHERE knowledge_base_id = #{kbId} AND status = 'done'")
    long sumChunksByKbId(@Param("kbId") Long kbId);
}
