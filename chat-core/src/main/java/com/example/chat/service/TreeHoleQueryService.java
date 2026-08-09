package com.example.chat.service;

import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.repository.TreeHoleRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 树洞历史查询服务（只读）
 */
@Service
public class TreeHoleQueryService {

    private final TreeHoleRepository treeHoleRepository;

    public TreeHoleQueryService(TreeHoleRepository treeHoleRepository) {
        this.treeHoleRepository = treeHoleRepository;
    }

    /** 获取当前用户的树洞历史（最多50条） */
    public List<TreeHoleMessage> getHistory(Long userId) {
        return treeHoleRepository.findByUserId(userId);
    }

    /** 最近 N 条历史 */
    public List<TreeHoleMessage> getRecentHistory(Long userId, int limit) {
        return treeHoleRepository.findRecentNByUserId(userId, limit);
    }

    /** 搜索历史（分页） */
    public List<TreeHoleMessage> searchHistory(Long userId, String keyword, int offset, int limit) {
        return treeHoleRepository.searchByKeyword(userId, keyword, offset, limit);
    }

    /** 搜索结果总数 */
    public int countSearchHistory(Long userId, String keyword) {
        return treeHoleRepository.countSearchByKeyword(userId, keyword);
    }

    /** 获取某条记录前后 5 条上下文 */
    public List<TreeHoleMessage> getContextAround(Long userId, Long msgId) {
        return treeHoleRepository.findContextAround(userId, msgId);
    }
}
