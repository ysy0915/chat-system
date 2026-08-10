package com.example.chat.util;

import org.apache.poi.xslf.usermodel.*;

import java.awt.*;
import java.io.FileOutputStream;
import java.io.IOException;

public class PptGenerator {

    private static final int W = 960;
    private static final int H = 540;
    private static final Color BG = new Color(15, 23, 42);
    private static final Color CARD_BG = new Color(30, 41, 59);
    private static final Color ACCENT = new Color(56, 189, 248);
    private static final Color ACCENT2 = new Color(168, 85, 247);
    private static final Color WHITE = new Color(241, 245, 249);
    private static final Color GRAY = new Color(148, 163, 184);
    private static final Color BORDER = new Color(71, 85, 105);
    private static final int TOTAL = 20;

    public static void main(String[] args) throws IOException {
        XMLSlideShow ppt = new XMLSlideShow();
        ppt.setPageSize(new Dimension(W, H));

        slide01(ppt);
        slide02(ppt);
        slide03(ppt);
        slide04(ppt);
        slide05(ppt);
        slide06(ppt);
        slide07(ppt);
        slide08(ppt);
        slide09(ppt);
        slide10(ppt);
        slide11(ppt);
        slide12(ppt);
        slide13(ppt);
        slide14(ppt);
        slide15(ppt);
        slide16(ppt);
        slide17(ppt);
        slide18(ppt);
        slide19(ppt);
        slide20(ppt);

        String out = "博思AI智能体-项目介绍.pptx";
        try (FileOutputStream fos = new FileOutputStream(out)) {
            ppt.write(fos);
        }
        ppt.close();
        System.out.println("PPT generated: " + out);
    }

    // ========== helpers ==========

    private static void bg(XSLFSlide s, Color c) {
        XSLFAutoShape r = s.createAutoShape();
        r.setAnchor(new Rectangle(0, 0, W, H));
        r.setFillColor(c);
        r.setLineWidth(0);
    }

    private static XSLFTextBox tb(XSLFSlide s, String txt, int x, int y, int w, int h,
                                   Color fc, double fs, boolean bold) {
        XSLFTextBox t = s.createTextBox();
        t.setAnchor(new Rectangle(x, y, w, h));
        t.setLineWidth(0);
        t.clearText();
        XSLFTextParagraph p = t.addNewTextParagraph();
        XSLFTextRun r = p.addNewTextRun();
        r.setText(txt);
        r.setFontColor(fc);
        r.setFontSize(fs);
        r.setBold(bold);
        r.setFontFamily("Microsoft YaHei");
        return t;
    }

    private static void card(XSLFSlide s, String title, String body,
                             int x, int y, int w, int h) {
        XSLFTextBox t = s.createTextBox();
        t.setAnchor(new Rectangle(x, y, w, h));
        t.setLineWidth(1);
        t.setLineColor(BORDER);
        t.setFillColor(CARD_BG);
        t.clearText();

        XSLFTextParagraph p1 = t.addNewTextParagraph();
        XSLFTextRun tr1 = p1.addNewTextRun();
        tr1.setText(title);
        tr1.setFontColor(ACCENT);
        tr1.setFontSize(13.0);
        tr1.setBold(true);
        tr1.setFontFamily("Microsoft YaHei");

        XSLFTextParagraph p2 = t.addNewTextParagraph();
        XSLFTextRun tr2 = p2.addNewTextRun();
        tr2.setText(body);
        tr2.setFontColor(GRAY);
        tr2.setFontSize(10.0);
        tr2.setFontFamily("Microsoft YaHei");
    }

    private static void section(XSLFSlide s, String title) {
        bg(s, BG);
        tb(s, title, 40, 18, 600, 40, ACCENT, 24, true);
        XSLFAutoShape line = s.createAutoShape();
        line.setAnchor(new Rectangle(40, 56, 120, 3));
        line.setFillColor(ACCENT);
        line.setLineWidth(0);
    }

    private static void num(XSLFSlide s, int n) {
        tb(s, n + " / " + TOTAL, 870, 510, 80, 20, GRAY, 9, false);
    }

    private static void accentLine(XSLFSlide s, int x, int y, int w) {
        XSLFAutoShape line = s.createAutoShape();
        line.setAnchor(new Rectangle(x, y, w, 3));
        line.setFillColor(ACCENT);
        line.setLineWidth(0);
    }

    // ========== slides ==========

    private static void slide01(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        bg(s, BG);
        tb(s, "博思AI智能体", 180, 110, 600, 60, WHITE, 40, true);
        tb(s, "多 AI 模型协作的企业级智能对话平台", 180, 180, 600, 40, ACCENT, 20, false);
        accentLine(s, 180, 230, 200);
        tb(s, "融合群聊 · 辩论 · 知识图谱 · 多模态生成 · AI游戏", 180, 250, 600, 30, GRAY, 14, false);
        tb(s, "制作者：杨思义", 180, 310, 300, 25, GRAY, 13, false);
        tb(s, "技术栈：Spring Boot 3 + React 18 + LangChain4j + RabbitMQ", 180, 340, 600, 25, GRAY, 12, false);
        tb(s, "v3.0 · 2026年8月", 180, 370, 300, 25, GRAY, 12, false);
    }

    private static void slide02(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "项目概述");
        num(s, 2);
        tb(s, "博思AI智能体是一个融合多模态AI能力与大数据分析的企业级智能平台，支持多AI模型协同对话、知识管理、内容生成等核心场景。", 40, 72, 880, 40, GRAY, 12, false);

        card(s, "AI伙伴群聊", "多AI模型同时参与公开对话\n支持流式输出与实时互动", 40, 125, 280, 85);
        card(s, "观点辩论场", "三AI并行辩论(千问/DeepSeek/豆包)\nLangGraph4j 结构化论点输出", 340, 125, 280, 85);
        card(s, "知识脉络图", "连接零散知识点成网\n可视化展示问题来龙去脉", 640, 125, 280, 85);
        card(s, "多模态生成", "文生图 / 文生视频\n图生3D模型(GLB/OBJ/STL)", 40, 225, 280, 85);
        card(s, "情绪树洞", "匿名情绪倾诉\nAI共情回复 + 内容安全过滤", 340, 225, 280, 85);
        card(s, "AI多人游戏", "城堡围攻 / 乒乓球 / 贪吃蛇\nAI参与的游戏体验", 640, 225, 280, 85);
        card(s, "在线监控", "实时在线人数统计(按页面分组)\n8天历史趋势曲线", 40, 325, 280, 85);
        card(s, "知识库RAG", "Milvus向量检索 + 文档解析\n文本分块 + 对话记忆融合", 340, 325, 280, 85);
        card(s, "Agent工具调用", "计算器/天气/时间/知识搜索\nLLM自主决定调用时机", 640, 325, 280, 85);

        tb(s, "六大核心功能 + 三大增强能力，覆盖AI对话、内容生成、知识管理全场景", 40, 430, 880, 30, GRAY, 11, false);
    }

    private static void slide03(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "系统架构总览 — 九层分层架构");
        num(s, 3);
        tb(s, "前后端分离 + 双实例集群 + 消息队列解耦", 40, 66, 800, 22, GRAY, 11, false);

        String[][] layers = {
            {"前端层", "React 18 SPA + WebSocket + Vite 5"},
            {"负载均衡层", "Nginx ip_hash 会话粘性 + 健康检查 + 故障转移"},
            {"网关层", "Spring Boot Gateway + JWT 鉴权 + 路由分发"},
            {"安全层", "Spring Security + IP限流 + 内容安全检测"},
            {"应用核心", "Java 稳态层 (用户/权限/消息/事务/游戏)"},
            {"AI服务层", "LangChain4j + LangGraph4j + 多LLM策略路由"},
            {"中间件层", "RabbitMQ 跨节点广播 + Redis 缓存/会话"},
            {"大数据层", "Spark + Flink + ClickHouse + Elasticsearch"},
            {"存储层", "MySQL + Redis + Milvus + Neo4j + MinIO"}
        };

        for (int i = 0; i < layers.length; i++) {
            int yp = 95 + i * 44;
            XSLFTextBox box = s.createTextBox();
            box.setAnchor(new Rectangle(40, yp, 880, 38));
            box.setLineWidth(1);
            box.setLineColor(BORDER);
            box.setFillColor(new Color(
                Math.max(15, 40 - i * 3),
                Math.max(30, 100 - i * 8),
                Math.max(50, 160 - i * 12)
            ));

            XSLFTextParagraph p = box.addNewTextParagraph();
            XSLFTextRun nr = p.addNewTextRun();
            nr.setText((i + 1) + ". ");
            nr.setFontColor(ACCENT);
            nr.setFontSize(11.0);
            nr.setBold(true);
            nr.setFontFamily("Microsoft YaHei");

            XSLFTextRun nameR = p.addNewTextRun();
            nameR.setText(layers[i][0] + "    ");
            nameR.setFontColor(WHITE);
            nameR.setFontSize(12.0);
            nameR.setBold(true);
            nameR.setFontFamily("Microsoft YaHei");

            XSLFTextRun descR = p.addNewTextRun();
            descR.setText(layers[i][1]);
            descR.setFontColor(GRAY);
            descR.setFontSize(10.0);
            descR.setFontFamily("Microsoft YaHei");
        }
    }

    private static void slide04(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "后端模块架构");
        num(s, 4);

        card(s, "chat-common\n公共库", "实体、DTO、安全工具\n拦截器、通用工具类\n跨模块共享基础设施", 40, 80, 200, 120);
        card(s, "chat-core\n核心AI服务 :9090", "LLM调用与策略路由\nRAG检索增强生成\nAgent工具调用\nLangGraph4j辩论引擎", 260, 80, 220, 120);
        card(s, "chat-web\nWeb接入层 :8080", "REST Controller\nWebSocket STOMP\nJWT鉴权过滤器\nAPI网关路由", 500, 80, 200, 120);
        card(s, "chat-games\n游戏服务 :8083", "城堡围攻\n乒乓球对战\n贪吃蛇多人", 720, 80, 200, 120);
        card(s, "chat-media\n多模态服务 :8084", "文生图(通义万相)\n文生视频(wan2.7-t2v)\n图生3D模型", 40, 220, 200, 110);

        tb(s, "模块间调用关系", 40, 350, 300, 25, ACCENT, 14, true);
        tb(s,
            "chat-web --HTTP--> chat-core (AI推理请求)\n" +
            "chat-web --HTTP--> chat-media (多模态生成)\n" +
            "chat-web --HTTP--> chat-games (游戏逻辑)\n" +
            "chat-core <--RabbitMQ--> chat-web (跨节点消息广播)\n" +
            "所有模块 --> chat-common (公共实体/工具)",
            40, 380, 880, 120, GRAY, 11, false);
    }

    private static void slide05(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "前端架构");
        num(s, 5);

        card(s, "路由策略", "BrowserRouter (/chat)\n常驻页面: 5个(KeepAlive保活)\n按需页面: 13个(懒加载+Suspense)\nHover预取: 鼠标悬停预加载", 40, 80, 280, 120);
        card(s, "状态管理", "事件驱动轻量方案\nlocalStorage 持久化\nCustomEvent 全局广播\naxios 401 自动拦截", 340, 80, 280, 120);
        card(s, "WebSocket通信", "SockJS + STOMP协议\n在线状态跟踪(按页面分组)\n5分钟无操作自动断开\n断线自动重连机制", 640, 80, 280, 120);

        tb(s, "16个页面路由", 40, 218, 300, 22, ACCENT, 13, true);

        String[][] pages = {
            {"首页 Landing", "在线人数/总用量展示"},
            {"AI伙伴群聊", "公开问答/多AI参与"},
            {"个人对话空间", "JWT认证/私密对话"},
            {"观点辩论场", "三AI并行辩论"},
            {"情绪树洞", "匿名倾诉/AI共情"},
            {"图片与视频生成", "多模态AI异步生成"},
            {"3D模型生成", "图生3D/GLB/OBJ/STL"},
            {"AI多人游戏", "城堡围攻/乒乓/贪吃蛇"},
            {"知识脉络图", "语义神经网络/3D可视化"},
            {"问答列表", "历史检索/摘要浏览"},
            {"知识库", "RAG文档管理"},
            {"监控页", "在线趋势/分页面统计"},
        };
        for (int i = 0; i < pages.length; i++) {
            int col = i % 3, row = i / 3;
            int x = 40 + col * 300, y = 245 + row * 52;
            tb(s, "* " + pages[i][0], x, y, 280, 16, WHITE, 10, true);
            tb(s, "  " + pages[i][1], x, y + 16, 280, 16, GRAY, 9, false);
        }
    }

    private static void slide06(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "核心功能: AI伙伴群聊");
        num(s, 6);
        tb(s, "多AI模型同时参与公开对话，支持流式输出与实时互动", 40, 66, 800, 22, GRAY, 11, false);

        String[] steps = {
            "1.用户输入问题", "2.限流+敏感词过滤", "3.消息入库(queued)", "4.RabbitMQ异步消费",
            "5.并发调用3个AI", "6.每个完成即推送", "7.WebSocket推送前端", "8.更新状态+Redis缓存"
        };
        for (int i = 0; i < steps.length; i++) {
            int x = 40 + (i % 4) * 225, y = 100 + (i / 4) * 60;
            XSLFTextBox box = s.createTextBox();
            box.setAnchor(new Rectangle(x, y, 210, 48));
            box.setLineWidth(1);
            box.setLineColor(ACCENT);
            box.setFillColor(CARD_BG);
            box.clearText();
            XSLFTextParagraph p = box.addNewTextParagraph();
            XSLFTextRun r = p.addNewTextRun();
            r.setText(steps[i]);
            r.setFontColor(WHITE);
            r.setFontSize(11.0);
            r.setFontFamily("Microsoft YaHei");
        }

        tb(s, "关键设计", 40, 240, 200, 22, ACCENT, 13, true);
        tb(s,
            "* handle + AtomicInteger 替代 allOf: 每个Future独立回调,完成即推送,避免竞态\n" +
            "* 三模型并发: CompletableFuture.supplyAsync 并行调用千问/DeepSeek/豆包\n" +
            "* MQ降级: RabbitMQ不可用时直接同步处理,保证可用性\n" +
            "* 自动问答: 1小时间隔模拟群聊活跃,避免新用户看到空白\n" +
            "* 模型切换: 用户输入\"换豆包\"等自动切换当前对话模型",
            40, 268, 880, 110, GRAY, 10, false);

        tb(s, "WebSocket 跨节点通信", 40, 390, 400, 22, ACCENT, 13, true);
        tb(s,
            "双实例部署下WebSocket Session是本地内存状态。通过RabbitMQ TopicExchange('cross-node')\n" +
            "实现消息广播,结合nodeId防回环机制,保障分布式环境下消息一致性。",
            40, 416, 880, 50, GRAY, 10, false);
    }

    private static void slide07(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "核心功能: 观点辩论场");
        num(s, 7);
        tb(s, "基于 LangGraph4j 实现三AI并行辩论，结构化论点输出", 40, 66, 800, 22, GRAY, 11, false);

        card(s, "辩论流程", "1. 用户输入辩题\n2. POST /api/v1/debate\n3. 并行调用3个AI模型\n4. 每个AI通过WS推送观点\n5. 支持后续追问深化讨论", 40, 105, 420, 150);
        card(s, "参与模型", "千问(Qwen) - 搜索增强,知识面广\nDeepSeek - 推理能力强,逻辑严密\n豆包(Doubao) - 响应速度快,表达流畅\n\n排除智谱,仅用以上三个模型", 480, 105, 440, 150);
        card(s, "LangGraph4j 状态图", "定义辩论状态机:\n  初始 -> 各方立论 -> 交叉质询 -> 总结陈词\n每个节点独立执行,支持条件分支\n结构化输出论点/论据/结论", 40, 275, 420, 130);
        card(s, "技术亮点", "* 三模型并发辩论,独立流式推送\n* 结构化论点输出(JSON格式)\n* 支持多轮追问深化讨论\n* 辩论记录持久化到 debate_records\n* 前端实时渲染三方观点对比", 480, 275, 440, 130);
    }

    private static void slide08(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "核心功能: 知识脉络图 & 知识库");
        num(s, 8);
        tb(s, "连接零散知识点成网，展示问题来龙去脉，基于语义相似度神经网络", 40, 66, 800, 22, GRAY, 11, false);

        card(s, "3D可视化", "基于3D地球视觉规范\n语义相似度神经网络展示\n节点大小反映关联度\n支持旋转/缩放/交互", 40, 105, 280, 130);
        card(s, "知识构建", "AI对话自动提取知识点\n语义关联自动发现\nNeo4j 图数据库存储\n支持手动添加/编辑节点", 340, 105, 280, 130);
        card(s, "交互功能", "搜索定位节点\n点击展开关联\n节点高亮联动\n实时更新图谱", 640, 105, 280, 130);

        card(s, "知识库 RAG", "Milvus 向量数据库 + Embedding 模型\n支持 PDF/Word/TXT 文档上传解析\n文本分块 -> 向量化 -> 存入 Milvus\n对话时自动检索知识库,融合回答\nLangChain4j ChatMemory 记忆融合", 40, 255, 420, 140);
        card(s, "Agent 工具调用", "Calculator - 数学计算\nWeather - 天气查询\nTime - 时间获取\nKnowledgeSearch - 知识库检索\n\nLLM 自主决定何时调用哪个工具\n工具结果自动融入对话上下文", 480, 255, 440, 140);
    }

    private static void slide09(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "核心功能: 多模态生成");
        num(s, 9);

        card(s, "文生图", "模型: 通义万相(Qwen-Image)\n提示词 -> 高质量图片\n异步调用模式\n支持多种尺寸和风格\n生成记录持久化", 40, 80, 280, 150);
        card(s, "文生视频", "模型: wan2.7-t2v(通义万相视频)\n提示词 -> 电影级视频\n异步调用 + 轮询获取结果\n视频时长可配置\n支持视频生成记录查询", 340, 80, 280, 150);
        card(s, "图生3D", "输入图片 -> 3D模型\n输出格式: GLB/OBJ/STL\n支持纹理映射\n可在线3D预览\n下载导出", 640, 80, 280, 150);

        tb(s, "异步调用机制", 40, 250, 300, 22, ACCENT, 13, true);
        tb(s,
            "1. 用户提交生成请求 -> POST /api/v1/media/generate\n" +
            "2. 后端调用阿里云API提交异步任务 -> 返回 taskId\n" +
            "3. 定时轮询任务状态(间隔3秒)\n" +
            "4. 任务完成后获取结果URL -> 入库 media_gen_records\n" +
            "5. WebSocket 通知前端生成完成\n" +
            "6. 前端展示生成结果,支持下载/预览",
            40, 278, 880, 130, GRAY, 10, false);
        tb(s, "chat-media 模块独立部署(端口8084),通过HTTP与chat-web通信", 40, 430, 880, 22, GRAY, 10, false);
    }

    private static void slide10(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "核心功能: 情绪树洞 & 个人对话");
        num(s, 10);

        card(s, "情绪树洞", "匿名情绪倾诉空间(需登录)\n支持选择心情标签 + 文字倾诉\n可附带图片表达\nAI生成温和共情回复\n内容安全过滤保护\n历史记录持久化", 40, 80, 420, 170);
        card(s, "个人对话空间", "JWT认证的私密对话\nLangChain4j ChatMemory\n短期记忆24小时\n长期记忆永久保存\n跨会话回忆相关历史\n支持图片拖拽上传\nAI自动识别图片内容", 480, 80, 440, 170);

        card(s, "对话记忆持久化", "短期记忆: 24小时TTL,Redis缓存\n长期记忆: MySQL持久化,永久保存\n记忆检索: 按语义相似度匹配\n记忆融合: 自动注入对话上下文\n跨会话: 新对话自动关联相关历史", 40, 270, 420, 140);
        card(s, "v2.1 新增功能", "* 重新生成 - 不满意可重新作答\n* 停止生成 - 流式输出随时停止\n* 对话摘要 - 自动15字摘要\n* 图片拖拽 - 拖拽上传自动识别\n* 语音输入 - 麦克风语音识别\n* 语音朗读 - AI回答语音播放", 480, 270, 440, 140);
    }

    private static void slide11(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "核心功能: AI多人游戏");
        num(s, 11);

        card(s, "城堡围攻", "多人在线策略游戏\n攻击/防御城堡\n实时WebSocket同步\nchat-games :8083", 40, 80, 280, 140);
        card(s, "乒乓球对战", "AI对手对战\n技能系统(加速/旋转/扣杀)\n两跳判负规则\n发球脱离球拍机制\n10球制计分", 340, 80, 280, 140);
        card(s, "贪吃蛇多人", "多人同场竞技\nAI蛇智能移动\n碰撞检测\n实时排名", 640, 80, 280, 140);

        tb(s, "技术实现", 40, 240, 200, 22, ACCENT, 13, true);
        tb(s,
            "* 独立 chat-games 模块部署(端口8083)\n" +
            "* WebSocket 实时双向通信,帧同步\n" +
            "* 按需加载(EPHEMERAL_ROUTES),离开即卸载,避免键盘事件冲突\n" +
            "* Canvas 渲染游戏画面,60fps流畅体验\n" +
            "* 乒乓球拍视觉样式: 渐变色 + 光影效果\n" +
            "* 发球机制: 球脱离球拍后独立运动,AI自动追踪",
            40, 268, 880, 130, GRAY, 10, false);
    }

    private static void slide12(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "技术栈全景");
        num(s, 12);

        String[][] techs = {
            {"前端", "React 18 + Vite 5 + Router 6 + Axios"},
            {"前端通信", "SockJS + STOMP (@stomp/stompjs)"},
            {"后端框架", "Spring Boot 3.1.6 (Java 17)"},
            {"微服务", "Spring Cloud + Nacos 服务发现"},
            {"WebSocket", "Spring WebSocket + STOMP 协议"},
            {"消息队列", "RabbitMQ (跨节点广播 + 业务消息)"},
            {"缓存", "Redis 7 (会话跟踪 + 结果缓存 + 在线统计)"},
            {"数据库", "MySQL 8.0 (业务数据持久化)"},
            {"AI引擎", "LangChain4j 0.34 + LangGraph4j"},
            {"向量数据库", "Milvus 2.3 (RAG知识检索)"},
            {"图数据库", "Neo4j 5.27 (知识图谱存储)"},
            {"鉴权", "JWT 自实现过滤器 + 短期Token"},
            {"内容安全", "阿里云内容安全 API"},
            {"AI模型", "千问 / DeepSeek / 豆包 (OpenAI兼容API)"},
            {"反向代理", "Nginx (健康检查 + 故障转移 + ip_hash)"},
            {"部署", "Docker + Docker Compose + Shell脚本"},
            {"代码质量", "JaCoCo 覆盖率 + SpotBugs 静态分析"},
            {"日志分析", "Kafka -> Flink -> Elasticsearch"},
        };

        for (int i = 0; i < techs.length; i++) {
            int col = i % 2, row = i / 2;
            int x = 40 + col * 460, y = 75 + row * 30;

            XSLFTextBox box = s.createTextBox();
            box.setAnchor(new Rectangle(x, y, 440, 26));
            box.setLineWidth(0);
            box.setFillColor(row % 2 == 0 ? CARD_BG : BG);
            box.clearText();

            XSLFTextParagraph p = box.addNewTextParagraph();
            XSLFTextRun nr = p.addNewTextRun();
            nr.setText(techs[i][0] + "  ");
            nr.setFontColor(ACCENT);
            nr.setFontSize(9.0);
            nr.setBold(true);
            nr.setFontFamily("Microsoft YaHei");

            XSLFTextRun vr = p.addNewTextRun();
            vr.setText(techs[i][1]);
            vr.setFontColor(GRAY);
            vr.setFontSize(9.0);
            vr.setFontFamily("Microsoft YaHei");
        }
    }

    private static void slide13(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "AI模型集成策略");
        num(s, 13);

        card(s, "千问 (Qwen)", "用途: 群聊/辩论/个人对话\n特点: 搜索增强,知识面广\nAPI: OpenAI兼容格式\n配置: model_configs表运行时切换", 40, 80, 420, 130);
        card(s, "DeepSeek", "用途: 群聊/辩论\n特点: 推理能力强,逻辑严密\nAPI: OpenAI兼容格式\n配置: 支持优先级路由", 480, 80, 440, 130);
        card(s, "豆包 (Doubao)", "用途: 群聊/辩论\n特点: 响应速度快,表达流畅\nAPI: OpenAI兼容格式\n支持用户主动切换", 40, 230, 420, 130);
        card(s, "通义万相", "用途: 图片生成/视频生成\n模型: qwen-image / wan2.7-t2v\n特点: 异步调用,高质量输出\n支持多模态输入", 480, 230, 440, 130);

        tb(s, "统一调用架构", 40, 380, 300, 22, ACCENT, 13, true);
        tb(s,
            "* 所有模型统一通过 OpenAI 兼容 API 调用, ModelRouter 策略路由\n" +
            "* 模型配置存储在 MySQL model_configs 表, 支持运行时动态切换\n" +
            "* 多模态: callLLMWithImage()处理图片, callLLMWithHistory()携带上下文\n" +
            "* 熔断器保护: 连续失败自动熔断,半开恢复,避免雪崩",
            40, 408, 880, 90, GRAY, 10, false);
    }

    private static void slide14(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "数据存储设计");
        num(s, 14);

        tb(s, "MySQL 核心表 (6张)", 40, 66, 300, 22, ACCENT, 13, true);

        String[][] tables = {
            {"messages", "群聊/个人对话消息", "req_id(UK), user_id, question, answer(JSON), status"},
            {"users", "用户账号信息", "id, email(UK), password_hash, name, role"},
            {"model_configs", "AI模型配置", "provider, model, api_key_encrypted, priority, enabled"},
            {"online_count_records", "在线人数历史快照", "page, count, snapshot_time"},
            {"treehole_messages", "情绪树洞对话记录", "user_id, mood, question, answer"},
            {"debate_records", "辩论场记录", "topic, model, arguments(JSON), created_at"},
        };
        for (int i = 0; i < tables.length; i++) {
            int y = 92 + i * 34;
            XSLFTextBox box = s.createTextBox();
            box.setAnchor(new Rectangle(40, y, 880, 30));
            box.setLineWidth(0);
            box.setFillColor(i % 2 == 0 ? CARD_BG : BG);
            box.clearText();
            XSLFTextParagraph p = box.addNewTextParagraph();
            XSLFTextRun t = p.addNewTextRun();
            t.setText(String.format("%-22s", tables[i][0]));
            t.setFontColor(ACCENT);
            t.setFontSize(9.0);
            t.setBold(true);
            t.setFontFamily("Consolas");
            XSLFTextRun d = p.addNewTextRun();
            d.setText(tables[i][1] + "  ");
            d.setFontColor(WHITE);
            d.setFontSize(9.0);
            d.setFontFamily("Microsoft YaHei");
            XSLFTextRun f = p.addNewTextRun();
            f.setText(tables[i][2]);
            f.setFontColor(GRAY);
            f.setFontSize(8.0);
            f.setFontFamily("Consolas");
        }

        tb(s, "Redis 数据结构", 40, 308, 300, 22, ACCENT, 13, true);

        String[][] rds = {
            {"ZSet", "session:{page}", "按页面分组的在线用户集合(score=时间戳)"},
            {"String", "online:count:{page}", "各页面实时在线人数"},
            {"Hash", "model:cache:{reqId}", "AI回复结果缓存(避免重复调用)"},
            {"String", "question:{sha256}", "问题答案缓存(TTL 24h)"},
            {"Counter", "rate:{uid}", "用户速率限制(令牌桶)"},
        };
        for (int i = 0; i < rds.length; i++) {
            int y = 334 + i * 28;
            XSLFTextBox box = s.createTextBox();
            box.setAnchor(new Rectangle(40, y, 880, 24));
            box.setLineWidth(0);
            box.setFillColor(i % 2 == 0 ? CARD_BG : BG);
            box.clearText();
            XSLFTextParagraph p = box.addNewTextParagraph();
            XSLFTextRun t1 = p.addNewTextRun();
            t1.setText(String.format("%-10s", rds[i][0]));
            t1.setFontColor(ACCENT2);
            t1.setFontSize(9.0);
            t1.setBold(true);
            t1.setFontFamily("Consolas");
            XSLFTextRun t2 = p.addNewTextRun();
            t2.setText(String.format("%-25s", rds[i][1]));
            t2.setFontColor(WHITE);
            t2.setFontSize(9.0);
            t2.setFontFamily("Consolas");
            XSLFTextRun t3 = p.addNewTextRun();
            t3.setText(rds[i][2]);
            t3.setFontColor(GRAY);
            t3.setFontSize(9.0);
            t3.setFontFamily("Microsoft YaHei");
        }
    }

    private static void slide15(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "安全防护体系");
        num(s, 15);

        tb(s, "五层纵深防护", 40, 66, 300, 22, ACCENT, 13, true);

        String[] layers = {
            "1. Nginx层 - IP限流(滑动窗口) + 自动拉黑异常IP",
            "2. 网关层 - JWT鉴权过滤器, 401自动清除前端登录态",
            "3. 应用层 - IpRateLimitInterceptor 滑动窗口限流 + UA过滤",
            "4. 内容层 - 阿里云内容安全 detectSensitive() 敏感词检测",
            "5. 前端层 - 前后端双重敏感词过滤 + AI生成内容标注"
        };
        for (int i = 0; i < layers.length; i++) {
            int y = 95 + i * 40;
            XSLFTextBox box = s.createTextBox();
            box.setAnchor(new Rectangle(40, y, 880, 34));
            box.setLineWidth(1);
            box.setLineColor(new Color(239, 68, 68));
            box.setFillColor(CARD_BG);
            box.clearText();
            XSLFTextParagraph p = box.addNewTextParagraph();
            XSLFTextRun r = p.addNewTextRun();
            r.setText(layers[i]);
            r.setFontColor(WHITE);
            r.setFontSize(11.0);
            r.setFontFamily("Microsoft YaHei");
        }

        tb(s, "安全设计要点", 40, 305, 300, 22, ACCENT, 13, true);
        tb(s,
            "* JWT 短期Token + 前端 axios 401 拦截器自动清除登录态\n" +
            "* API Key 加密存储(api_key_encrypted), 绝不明文\n" +
            "* 幂等设计: 客户端生成 req_id(UUID), 服务端 UNIQUE(req_id) 防重复\n" +
            "* 速率限制: Redis rate:{uid} 令牌桶, 防刷保护\n" +
            "* 内容安全: 前后端双重过滤, AI生成内容标注\"AI生成\"标识\n" +
            "* 前端错误上报: ErrorBoundary + window.error + unhandledrejection 三路捕获",
            40, 332, 880, 140, GRAY, 10, false);
    }

    private static void slide16(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "可观测性体系");
        num(s, 16);

        card(s, "熔断器", "模型连续失败自动熔断\n半开探测恢复\n避免级联故障\n保护系统稳定性", 40, 80, 280, 120);
        card(s, "错误聚合", "按模型/错误类型统计\n聚合分析错误趋势\n快速定位问题模型\n支持告警阈值", 340, 80, 280, 120);
        card(s, "调用链追踪", "TraceContext 全链路传递\nREST->MQ->Consumer->LLM\n每步耗时可追踪\n日志关联分析", 640, 80, 280, 120);
        card(s, "自愈服务", "NETWORK_ERROR 自动重试\n降级策略(MQ不可用时同步)\n健康检查(actuator/health)\n自动恢复机制", 40, 220, 280, 120);
        card(s, "日志分析", "Kafka->Flink->ES 实时流\n结构化JSON日志\ntrace_id 贯穿全链路\n前端异常上报", 340, 220, 280, 120);
        card(s, "在线监控", "Redis ZSet 按页面分组\n8天历史趋势曲线\nCanvas 实时绘制\n1小时峰值统计", 640, 220, 280, 120);

        tb(s, "代码质量保障", 40, 360, 300, 22, ACCENT, 13, true);
        tb(s,
            "* JaCoCo 代码覆盖率: 95.3% 源文件覆盖, 434 tests / 0 failures\n" +
            "* SpotBugs 静态分析 + FindSecBugs 安全扫描\n" +
            "* CI Profile: 覆盖率阈值检查 + SpotBugs 强制校验\n" +
            "* 综合评分: 72/100 (良好)",
            40, 388, 880, 90, GRAY, 10, false);
    }

    private static void slide17(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "部署架构");
        num(s, 17);

        card(s, "开发环境", "macOS 本地开发\nbrew install redis rabbitmq\nmvn clean install -DskipTests\ncd frontend && npm run dev\n前端热更新 + 后端多实例", 40, 80, 280, 140);
        card(s, "Docker 部署", "docker-compose 三套环境\ndev: 开发(中间件)\nprod: 生产(仅中间件)\nall: 完整部署(全栈)\n健康检查 + 自动重启", 340, 80, 280, 140);
        card(s, "生产部署", "阿里云服务器\nNginx 反向代理 :8080\n双实例 8081/8082\nShell脚本自动化\nscp + restart.sh", 640, 80, 280, 140);

        tb(s, "部署拓扑", 40, 240, 300, 22, ACCENT, 13, true);
        tb(s,
            "用户浏览器 (React SPA)\n" +
            "       | HTTPS / WebSocket\n" +
            "       v\n" +
            "Nginx 反向代理 (:8080) <- ip_hash 会话粘性 + 健康检查\n" +
            "       |\n" +
            "  +----+----+\n" +
            "  v         v\n" +
            "8081       8082    <- Spring Boot 双实例(滚动重启零停机)\n" +
            "  |         |\n" +
            "  +----+----+\n" +
            "  |    |    |\n" +
            "  v    v    v\n" +
            "MySQL Redis RabbitMQ  <- 数据层 + 消息层\n" +
            "  |\n" +
            "  v\n" +
            "千问 / DeepSeek / 豆包  <- AI模型层",
            40, 268, 500, 230, GRAY, 9, false);

        tb(s, "JVM 关键参数", 560, 268, 300, 22, ACCENT, 13, true);
        tb(s,
            "-Dfile.encoding=UTF-8\n" +
            "-Dsun.jnu.encoding=UTF-8\n" +
            "(防止中文日志变?????)\n\n" +
            "actuator health 健康检查\n" +
            "等待启动完成后再接入流量",
            560, 296, 360, 120, GRAY, 10, false);
    }

    private static void slide18(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "性能与质量指标");
        num(s, 18);

        card(s, "测试覆盖", "434 测试用例\n0 失败\n95.3% 源文件覆盖率\nJaCoCo + SpotBugs", 40, 80, 200, 120);
        card(s, "综合评分", "72/100 良好\n\n测试覆盖 24/25\n代码规范 12/15\n架构设计 11/15", 260, 80, 200, 120);
        card(s, "安全评级", "4/5 安全性\n\nJWT + 限流\n内容安全检测\n五层纵深防护", 480, 80, 200, 120);
        card(s, "架构评分", "11/15 架构设计\n\n五模块清晰分层\nDocker 完备\nCI/CD 就绪", 700, 80, 220, 120);

        tb(s, "关键优化策略", 40, 220, 300, 22, ACCENT, 13, true);
        tb(s,
            "* 并发调用: CompletableFuture 三模型并行, 响应时间=max(单模型) 而非 sum\n" +
            "* Redis缓存: question cache TTL 24h, 命中率优化, 避免重复调用\n" +
            "* 前端懒加载: 13个页面按需挂载 + hover预取, 首屏体积优化\n" +
            "* KeepAlive保活: 5个常用页面常驻, 切换零延迟\n" +
            "* MQ异步解耦: 消息队列削峰填谷, 提高系统吞吐量\n" +
            "* Nginx故障转移: proxy_next_upstream 自动切换健康节点\n" +
            "* 5分钟空闲断开: 减少无效WebSocket连接, 释放服务器资源",
            40, 248, 880, 150, GRAY, 10, false);

        tb(s, "评分明细", 40, 415, 200, 22, ACCENT, 13, true);
        tb(s,
            "测试覆盖 24/25 | 测试质量 8/20 | 代码规范 12/15 | 架构设计 11/15\n" +
            "文档 7/10 | CI/CD 6/10 | 安全性 4/5 | 综合 72/100",
            40, 442, 880, 50, GRAY, 10, false);
    }

    private static void slide19(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        section(s, "未来规划");
        num(s, 19);

        card(s, "功能扩展", "多租户支持\n模型A/B测试与优先级路由\n异步通知(邮件/Slack)\n更多AI游戏\n语音对话实时交互", 40, 80, 280, 150);
        card(s, "大数据深化", "Spark 批处理/ETL/ML\nFlink 实时流计算/CEP\nClickHouse OLAP分析\nHive 统一元数据\nAirflow 任务调度", 340, 80, 280, 150);
        card(s, "架构演进", "Kafka 替换 RabbitMQ(高吞吐)\nElasticsearch 全文检索增强\nHBase 列式海量数据\nHDFS 分布式文件存储\nMinIO 对象存储", 640, 80, 280, 150);
        card(s, "安全合规", "ICP 备案办理\n生成式AI服务信息登记\n隐私声明完善\n操作审计日志\n数据加密传输", 40, 250, 280, 140);
        card(s, "测试增强", "Mock 单元测试增强\n集成测试覆盖\n压力测试(k6)\n混沌测试\n性能基准测试", 340, 250, 280, 140);
        card(s, "体验优化", "移动端适配完善\nPWA 离线支持\n国际化多语言\n无障碍访问\n主题切换", 640, 250, 280, 140);
    }

    private static void slide20(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        bg(s, BG);
        tb(s, "感谢观看", 280, 140, 400, 60, WHITE, 40, true);
        accentLine(s, 380, 210, 200);
        tb(s, "博思AI智能体 v3.0", 280, 230, 400, 30, ACCENT, 18, false);
        tb(s, "制作者: 杨思义", 280, 280, 400, 25, GRAY, 14, false);
        tb(s, "GitHub: https://github.com/ysy0915/chat-system", 180, 340, 600, 25, GRAY, 11, false);
        tb(s, "技术栈: Spring Boot 3 + React 18 + LangChain4j + RabbitMQ + Milvus", 180, 370, 600, 25, GRAY, 11, false);
    }
}
