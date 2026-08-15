package com.example.chat.llm.rag.legacy;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <h2>RAG 分片全文检索仓储（BM25 混合检索的关键词通道）</h2>
 *
 * <p>数据表 {@code rag_chunks}：在知识库分片向量化入库的同时，把分片文本冗余落一份到
 * MySQL，建 ngram 全文索引（支持中文），实现关键词召回，与向量召回做 RRF 融合。</p>
 *
 * <p>建表由 {@link KeywordSearchService} 启动时幂等执行（CREATE TABLE IF NOT EXISTS）。</p>
 */
@Mapper
public interface RagChunkRepository {

    /** 批量写入分片（一条 INSERT 多值，分片量通常数百以内） */
    @Insert("<script>INSERT INTO rag_chunks (kb_id, doc_id, chunk_index, source, page, text) VALUES "
            + "<foreach collection='chunks' item='c' separator=','>"
            + "(#{kbId}, #{docId}, #{c.chunkIndex}, #{source}, #{c.page}, #{c.text})"
            + "</foreach></script>")
    int insertBatch(@Param("kbId") Long kbId,
                    @Param("docId") Long docId,
                    @Param("source") String source,
                    @Param("chunks") List<VectorStoreLegacy.ChunkText> chunks);

    /** MySQL ngram 全文检索：按相关性得分降序返回命中分片 */
    @Select("SELECT id, kb_id, doc_id, chunk_index, source, page, text, "
            + "MATCH(text) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score "
            + "FROM rag_chunks "
            + "WHERE kb_id = #{kbId} AND MATCH(text) AGAINST(#{query} IN NATURAL LANGUAGE MODE) > 0 "
            + "ORDER BY score DESC LIMIT #{limit}")
    List<RagChunkRow> keywordSearch(@Param("kbId") Long kbId,
                                    @Param("query") String query,
                                    @Param("limit") int limit);

    /** 删除知识库下所有分片（删知识库时调用） */
    @Delete("DELETE FROM rag_chunks WHERE kb_id = #{kbId}")
    int deleteByKb(@Param("kbId") Long kbId);

    /** 删除单个文档的所有分片（删文档时调用） */
    @Delete("DELETE FROM rag_chunks WHERE doc_id = #{docId}")
    int deleteByDoc(@Param("docId") Long docId);

    /** rag_chunks 表行（keywordSearch 结果） */
    final class RagChunkRow {
        public Long id;
        public Long kbId;
        public Long docId;
        public Integer chunkIndex;
        public String source;
        public Integer page;
        public String text;
        /** MySQL FULLTEXT 相关性得分（值域约 0~10+，需归一化后使用） */
        public Double score;
    }
}
