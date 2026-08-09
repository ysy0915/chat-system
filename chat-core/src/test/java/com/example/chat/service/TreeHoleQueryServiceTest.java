package com.example.chat.service;

import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.repository.TreeHoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TreeHoleQueryService 单元测试
 * 所有方法纯委托 TreeHoleRepository，无业务逻辑
 */
@ExtendWith(MockitoExtension.class)
class TreeHoleQueryServiceTest {

    @Mock
    private TreeHoleRepository treeHoleRepository;

    private TreeHoleQueryService service;

    @BeforeEach
    void setUp() {
        service = new TreeHoleQueryService(treeHoleRepository);
    }

    @Test
    @DisplayName("getHistory 委托仓库返回历史列表")
    void getHistory_returnsHistory() {
        List<TreeHoleMessage> expected = List.of(new TreeHoleMessage());
        when(treeHoleRepository.findByUserId(1L)).thenReturn(expected);

        List<TreeHoleMessage> result = service.getHistory(1L);

        assertSame(expected, result);
        verify(treeHoleRepository).findByUserId(1L);
    }

    @Test
    @DisplayName("getHistory 仓库返回空列表时正常返回")
    void getHistory_whenEmpty() {
        when(treeHoleRepository.findByUserId(99L)).thenReturn(List.of());

        List<TreeHoleMessage> result = service.getHistory(99L);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getRecentHistory 传递 limit 参数给仓库")
    void getRecentHistory_respectsLimit() {
        when(treeHoleRepository.findRecentNByUserId(1L, 5)).thenReturn(List.of());

        service.getRecentHistory(1L, 5);

        verify(treeHoleRepository).findRecentNByUserId(1L, 5);
    }

    @Test
    @DisplayName("searchHistory 委托仓库搜索并返回结果")
    void searchHistory_delegatesToRepository() {
        List<TreeHoleMessage> expected = List.of(new TreeHoleMessage());
        when(treeHoleRepository.searchByKeyword(1L, "test", 0, 10)).thenReturn(expected);

        List<TreeHoleMessage> result = service.searchHistory(1L, "test", 0, 10);

        assertSame(expected, result);
    }

    @Test
    @DisplayName("countSearchHistory 委托仓库返回计数")
    void countSearchHistory_returnsCount() {
        when(treeHoleRepository.countSearchByKeyword(1L, "hello")).thenReturn(5);

        int count = service.countSearchHistory(1L, "hello");

        assertEquals(5, count);
        verify(treeHoleRepository).countSearchByKeyword(1L, "hello");
    }

    @Test
    @DisplayName("getContextAround 委托仓库返回上下文")
    void getContextAround_delegatesToRepository() {
        List<TreeHoleMessage> expected = List.of(new TreeHoleMessage());
        when(treeHoleRepository.findContextAround(1L, 100L)).thenReturn(expected);

        List<TreeHoleMessage> result = service.getContextAround(1L, 100L);

        assertSame(expected, result);
    }
}
