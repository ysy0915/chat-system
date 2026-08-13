package com.example.chat.service;

import com.example.chat.client.RagClient;
import com.example.chat.intent.IntentCategory;
import com.example.chat.intent.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 对话自动 RAG 增强器。
 *
 * <p>从 ChatProcessor 拆分：隔离「是否检索判定 → 知识库检索 → system prompt 构建」逻辑，
 * 供个人对话与群聊并发两条链路复用。</p>
 */
@Component
public class ChatRagEnhancer {

    private static final Logger log = LoggerFactory.getLogger(ChatRagEnhancer.class);

    private final RagClient ragClient;

    /** 对话自动 RAG 增强开关 */
    @Value("${app.rag.chat.enabled:true}")
    private boolean chatRagEnabled;

    /** 对话自动 RAG 默认检索的知识库 ID（<=0 表示未配置，不增强） */
    @Value("${app.rag.chat.kb-id:0}")
    private long chatRagKbId;

    /** 对话自动 RAG 检索 topK */
    @Value("${app.rag.chat.top-k:3}")
    private int chatRagTopK;

    /** 对话自动 RAG 相似度阈值 */
    @Value("${app.rag.chat.score-threshold:0.3}")
    private float chatRagScoreThreshold;

    /** 对话自动 RAG 参考资料的字符上限 */
    @Value("${app.rag.chat.max-chars:2000}")
    private int chatRagMaxChars;

    /** 实时数据类问题：知识库无此类内容，跳过检索（由工具/模型实时回答） */
    private static final Pattern[] REALTIME_PATTERNS = {
            Pattern.compile("(今天|明天|后天|现在|当前|这几天|最近).*(天气|气温|温度|多少度|几度|下雨|下雪|有雨|晴天|阴天|降温|刮风)"),
            Pattern.compile("(天气|气温|温度|天气预报).*(怎么样|如何|怎样|多少|几度|适合|穿|出门)"),
            Pattern.compile("(几点|几点了|现在几点|现在时间|今天星期几|星期几|几月几号|今天是)"),
            Pattern.compile("(今天|今日|最新|热点).*(新闻|时事|头条|快讯)"),
            Pattern.compile("(股票|股价|行情|汇率|金价|油价|大盘|基金|涨跌|A股|港股)"),
            Pattern.compile("(比分|比赛结果|赛果|赛况)"),
    };

    /** 个人数据类问题：知识库无个人数据，跳过检索 */
    private static final Pattern[] PERSONAL_PATTERNS = {
            Pattern.compile("我的(订单|账户|余额|消息|设置|资料|记录|聊天|历史|收藏|足迹|状态|积分|会员)"),
    };

    public ChatRagEnhancer(RagClient ragClient) {
        this.ragClient = ragClient;
    }

    public long getKbId() {
        return chatRagKbId;
    }

    /**
     * 是否需要自动检索知识库增强回答。
     *
     * <p>三层判定：</p>
     * 1. 开关/配置：启用且配置了默认知识库；
     * 2. 意图判定：知识问答（KNOWLEDGE_QA）或任务执行（TASK_EXECUTION）——概念/资料性查询；
     * 3. 可查性判定：排除实时数据类（天气、时间、新闻、行情、比分）与个人数据类（我的订单/消息）——
     *    知识库中不存在此类内容，检索只会浪费一次 Embedding，改由工具或模型实时回答。
     */
    public boolean shouldAutoRag(IntentResult intent, String question) {
        if (!chatRagEnabled || chatRagKbId <= 0 || ragClient == null) return false;
        if (intent == null || intent.category() == null) return false;
        IntentCategory c = intent.category();
        if (c != IntentCategory.KNOWLEDGE_QA && c != IntentCategory.TASK_EXECUTION) return false;
        return !isRealTimeOrPersonalQuery(question);
    }

    /** 判断问题是否为实时数据/个人数据类查询（知识库中不存在此类内容） */
    public boolean isRealTimeOrPersonalQuery(String question) {
        if (question == null || question.isBlank()) return false;
        for (Pattern p : REALTIME_PATTERNS) {
            if (p.matcher(question).find()) return true;
        }
        for (Pattern p : PERSONAL_PATTERNS) {
            if (p.matcher(question).find()) return true;
        }
        return false;
    }

    /**
     * 检索默认知识库，构建 RAG 参考资料（无命中返回 null，检索失败不影响主流程）。
     */
    public String buildContext(String question) {
        if (question == null || question.isBlank()) return null;
        try {
            List<RagClient.SearchResult> results = ragClient.search(chatRagKbId, question, chatRagTopK);
            if (results == null || results.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            int total = 0;
            for (RagClient.SearchResult r : results) {
                if (r.score() < chatRagScoreThreshold) continue;
                if (total + r.text().length() > chatRagMaxChars) break;
                sb.append("--- 资料（相似度 ").append(String.format("%.2f", r.score())).append("）---\n");
                sb.append(r.text()).append("\n\n");
                total += r.text().length();
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("[ChatRAG] 知识库检索失败 kb={} err={}", chatRagKbId, e.getMessage());
            return null;
        }
    }

    /** 构建 RAG 增强的 system prompt（引导依据知识库资料作答） */
    public String buildSystemPrompt(String context) {
        return "以下是用户知识库中检索到的参考资料。回答时请优先依据参考资料作答，"
                + "如果参考资料不足以回答，可结合你的知识回答并简要说明。\n\n"
                + "【参考资料】\n" + context;
    }
}
