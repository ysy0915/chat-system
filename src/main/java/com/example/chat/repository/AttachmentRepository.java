package com.example.chat.repository;

import com.example.chat.entity.Attachment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AttachmentRepository {
    @Insert("INSERT INTO attachments (message_id, uploaded_by, storage_url, mime_type, filename, size, created_at) VALUES (#{messageId}, #{uploadedBy}, #{storageUrl}, #{mimeType}, #{filename}, #{size}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Attachment a);

    @Select("SELECT id, message_id AS messageId, uploaded_by AS uploadedBy, storage_url AS storageUrl, mime_type AS mimeType, filename, size, created_at AS createdAt FROM attachments WHERE id = #{id}")
    Attachment findById(Long id);
}
