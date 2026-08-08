package com.example.chat.repository;

import com.example.chat.entity.MediaGenRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MediaGenRecordRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<MediaGenRecord> mapper = (rs, rowNum) -> {
        MediaGenRecord r = new MediaGenRecord();
        r.id = rs.getLong("id");
        r.userId = rs.getLong("user_id");
        r.prompt = rs.getString("prompt");
        r.mediaType = rs.getString("media_type");
        r.model = rs.getString("model");
        r.mediaUrl = rs.getString("media_url");
        r.glbUrl = rs.getString("glb_url");
        r.objUrl = rs.getString("obj_url");
        r.previewUrl = rs.getString("preview_url");
        r.status = rs.getString("status");
        r.errorMsg = rs.getString("error_msg");
        r.createdAt = rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null;
        return r;
    };

    public long insert(MediaGenRecord r) {
        String sql = "INSERT INTO media_gen_records (user_id, prompt, media_type, model, media_url, glb_url, obj_url, preview_url, status, error_msg) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, r.userId, r.prompt, r.mediaType, r.model, r.mediaUrl, r.glbUrl, r.objUrl, r.previewUrl, r.status, r.errorMsg);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void updateToDone(Long id, String mediaUrl, String glbUrl, String objUrl, String previewUrl) {
        String sql = "UPDATE media_gen_records SET status='done', media_url=?, glb_url=?, obj_url=?, preview_url=? WHERE id=?";
        jdbcTemplate.update(sql, mediaUrl, glbUrl, objUrl, previewUrl, id);
    }

    public void updateToError(Long id, String errorMsg) {
        String sql = "UPDATE media_gen_records SET status='error', error_msg=? WHERE id=?";
        jdbcTemplate.update(sql, errorMsg, id);
    }

    public MediaGenRecord findById(Long id) {
        String sql = "SELECT * FROM media_gen_records WHERE id = ?";
        List<MediaGenRecord> list = jdbcTemplate.query(sql, mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<MediaGenRecord> findRunningByUserId(Long userId) {
        String sql = "SELECT * FROM media_gen_records WHERE user_id = ? AND status = 'running' ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, mapper, userId);
    }

    public List<MediaGenRecord> findByUserIdOrderByCreatedAtDesc(Long userId, int limit) {
        String sql = "SELECT * FROM media_gen_records WHERE user_id = ? ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, mapper, userId, limit);
    }

    public List<MediaGenRecord> findByUserIdAndType(Long userId, String mediaType, int limit) {
        String sql = "SELECT * FROM media_gen_records WHERE user_id = ? AND media_type = ? ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, mapper, userId, mediaType, limit);
    }
}
