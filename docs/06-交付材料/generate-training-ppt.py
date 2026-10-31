# -*- coding: utf-8 -*-
"""生成《从 0 到 1 做企业级 AI 应用》培训 PPT（以 chat-system 项目为蓝本）。

用法（在任意目录执行均可，输出路径按本脚本所在目录自动推导）：
    python3 docs/06-交付材料/generate-training-ppt.py

依赖：python-pptx（缺失时先执行 pip install python-pptx）
内容来源：docs/08-项目复盘/、AI-ENGINEERING-CHECKLIST.md、README.md
以及近期运维事故记录；素材更新后重跑本脚本即可覆盖同名 pptx。
"""
import os
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn

FONT = 'PingFang SC' if os.path.exists('/System/Library/Fonts/PingFang.ttc') else 'Microsoft YaHei'
W, H = 13.333, 7.5

PRIMARY = RGBColor(0x0F, 0x2A, 0x47)
ACCENT = RGBColor(0x25, 0x63, 0xEB)
CYAN = RGBColor(0x0E, 0xA5, 0xE9)
GREEN = RGBColor(0x0F, 0x76, 0x6E)
AMBER = RGBColor(0xB4, 0x53, 0x09)
RED = RGBColor(0xC2, 0x2A, 0x22)
TEXT = RGBColor(0x1F, 0x29, 0x37)
MUTED = RGBColor(0x64, 0x74, 0x8B)
SOFT = RGBColor(0xF1, 0xF5, 0xF9)
LINE = RGBColor(0xE2, 0xE8, 0xF0)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
PALE = RGBColor(0xE8, 0xF1, 0xFD)
COVER_SOFT = RGBColor(0xBF, 0xD4, 0xEA)
PALE_RED = RGBColor(0xFD, 0xF1, 0xF1)
PALE_GREEN = RGBColor(0xE6, 0xF5, 0xF1)

prs = Presentation()
prs.slide_width = Inches(W)
prs.slide_height = Inches(H)
_page = {'n': 0}


def _font(run, size=16, bold=False, color=TEXT, name=FONT):
    f = run.font
    f.size = Pt(size)
    f.bold = bold
    f.color.rgb = color
    f.name = name
    rPr = run._r.get_or_add_rPr()
    ea = rPr.find(qn('a:ea'))
    if ea is None:
        ea = rPr.makeelement(qn('a:ea'), {})
        rPr.append(ea)
    ea.set('typeface', name)
    return run


def slide():
    return prs.slides.add_slide(prs.slide_layouts[6])


def rect(s, left, top, width, height, fill=None, line=None,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, adj=0.08):
    sp = s.shapes.add_shape(shape, Inches(left), Inches(top), Inches(width), Inches(height))
    if fill is None:
        sp.fill.background()
    else:
        sp.fill.solid()
        sp.fill.fore_color.rgb = fill
    if line is None:
        sp.line.fill.background()
    else:
        sp.line.color.rgb = line
        sp.line.width = Pt(1)
    sp.shadow.inherit = False
    try:
        sp.adjustments[0] = adj
    except Exception:
        pass
    return sp


def textbox(s, left, top, width, height):
    tb = s.shapes.add_textbox(Inches(left), Inches(top), Inches(width), Inches(height))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0
    return tf


def para(tf, text, size=16, bold=False, color=TEXT, align=PP_ALIGN.LEFT,
         space_after=8, space_before=0, bullet=None, line=1.25, first=False):
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.alignment = align
    p.space_after = Pt(space_after)
    p.space_before = Pt(space_before)
    p.line_spacing = line
    r = p.add_run()
    r.text = (bullet + '  ' + text) if bullet else text
    _font(r, size=size, bold=bold, color=color)
    return p


def bullets(s, left, top, width, items, size=15, gap=9, color=TEXT):
    tf = textbox(s, left, top, width, 0.4)
    for i, it in enumerate(items):
        txt, lvl = it if isinstance(it, tuple) else (it, 0)
        para(tf, txt, size=(size if lvl == 0 else size - 1), first=(i == 0),
             space_after=gap, bullet=('●' if lvl == 0 else '○'),
             color=(color if lvl == 0 else MUTED))
    return tf


def band(s, top, text, size=15, color=ACCENT, fill=PALE, left=0.6, width=12.13, height=0.62):
    rect(s, left, top, width, height, fill=fill, adj=0.15)
    tf = textbox(s, left + 0.28, top + 0.11, width - 0.56, height - 0.2)
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    para(tf, text, size=size, bold=True, color=color, first=True, space_after=0)


def frame(s, kicker, title, subtitle=None):
    _page['n'] += 1
    rect(s, 0, 0, W, H, fill=WHITE, shape=MSO_SHAPE.RECTANGLE)
    if kicker:
        tf = textbox(s, 0.6, 0.34, 11.5, 0.28)
        para(tf, kicker.upper() if kicker.isascii() else kicker, size=12, bold=True,
             color=ACCENT, first=True, space_after=0)
    tf = textbox(s, 0.6, 0.58, 12.1, 0.5)
    para(tf, title, size=27, bold=True, color=PRIMARY, first=True, space_after=0)
    if subtitle:
        tf2 = textbox(s, 0.6, 1.12, 12.1, 0.3)
        para(tf2, subtitle, size=13, color=MUTED, first=True, space_after=0)
    rect(s, 0.6, 1.52, 12.13, 0.02, fill=LINE, shape=MSO_SHAPE.RECTANGLE)
    tf3 = textbox(s, 11.6, 7.02, 1.13, 0.28)
    para(tf3, str(_page['n']), size=10, color=MUTED, align=PP_ALIGN.RIGHT, first=True, space_after=0)


def notes(s, text):
    try:
        s.notes_slide.notes_text_frame.text = text
    except Exception:
        pass


def table(s, left, top, width, headers, rows, col_widths=None,
          fs=12, head_fs=12, row_h=0.34, head_h=0.42):
    shape = s.shapes.add_table(len(rows) + 1, len(headers), Inches(left), Inches(top),
                               Inches(width), Inches(head_h + row_h * len(rows)))
    tbl = shape.table
    tbl.first_row = True
    tbl.horz_banding = False
    if col_widths:
        total = sum(col_widths)
        for i, cw in enumerate(col_widths):
            tbl.columns[i].width = Inches(width * cw / total)
    tbl.rows[0].height = Inches(head_h)
    for i in range(len(rows)):
        tbl.rows[i + 1].height = Inches(row_h)
    for c, htxt in enumerate(headers):
        cell = tbl.cell(0, c)
        cell.fill.solid()
        cell.fill.fore_color.rgb = PRIMARY
        cell.margin_left = Inches(0.1)
        cell.margin_top = Inches(0.04)
        cell.vertical_anchor = MSO_ANCHOR.MIDDLE
        para(cell.text_frame, htxt, size=head_fs, bold=True, color=WHITE, first=True, space_after=0)
    for r, row in enumerate(rows):
        for c, val in enumerate(row):
            cell = tbl.cell(r + 1, c)
            cell.fill.solid()
            cell.fill.fore_color.rgb = SOFT if r % 2 == 0 else WHITE
            cell.margin_left = Inches(0.1)
            cell.margin_top = Inches(0.03)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            strong = (c == 0)
            para(cell.text_frame, str(val), size=fs, bold=strong,
                 color=(PRIMARY if strong else TEXT), first=True, space_after=0, line=1.15)
    return tbl


def cards(s, top, items, left=0.6, width=12.13, gap=0.22, height=2.55, fs=12, tfs=14):
    n = len(items)
    cw = (width - gap * (n - 1)) / n
    for i, it in enumerate(items):
        x = left + i * (cw + gap)
        color = it.get('color', ACCENT)
        rect(s, x, top, cw, height, fill=SOFT, line=LINE, adj=0.05)
        rect(s, x, top, cw, 0.07, fill=color, shape=MSO_SHAPE.RECTANGLE)
        num = it.get('num')
        if num:
            tf = textbox(s, x + 0.16, top + 0.2, cw - 0.32, 0.26)
            para(tf, num, size=11, bold=True, color=color, first=True, space_after=0)
        tf = textbox(s, x + 0.16, top + 0.48, cw - 0.32, 0.3)
        para(tf, it.get('title', ''), size=tfs, bold=True, color=PRIMARY, first=True, space_after=0)
        tf = textbox(s, x + 0.16, top + 0.88, cw - 0.32, height - 1.0)
        for j, b in enumerate(it.get('body', [])):
            para(tf, b, size=fs, bullet='·', color=TEXT, first=(j == 0), space_after=5, line=1.2)


def divider(stage, title, subtitle, color):
    global s
    s = slide()
    _page['n'] += 1
    rect(s, 0, 0, W, H, fill=PRIMARY, shape=MSO_SHAPE.RECTANGLE)
    rect(s, 0, 0, 0.28, H, fill=color, shape=MSO_SHAPE.RECTANGLE)
    tf = textbox(s, 1.1, 2.2, 11, 0.5)
    para(tf, stage, size=18, bold=True, color=color, first=True, space_after=0)
    tf = textbox(s, 1.1, 2.85, 11, 1.2)
    para(tf, title, size=36, bold=True, color=WHITE, first=True, space_after=0, line=1.15)
    tf = textbox(s, 1.1, 4.3, 11, 0.6)
    para(tf, subtitle, size=15, color=COVER_SOFT, first=True, space_after=0)
    tf = textbox(s, 11.6, 7.02, 1.13, 0.28)
    para(tf, str(_page['n']), size=10, color=RGBColor(0x64, 0x84, 0xA8),
         align=PP_ALIGN.RIGHT, first=True, space_after=0)


# ============ 封面 ============
s = slide()
rect(s, 0, 0, W, H, fill=PRIMARY, shape=MSO_SHAPE.RECTANGLE)
rect(s, 0, 0, 0.28, H, fill=ACCENT, shape=MSO_SHAPE.RECTANGLE)
rect(s, 8.4, 0, 4.93, H, fill=RGBColor(0x14, 0x3A, 0x5E), shape=MSO_SHAPE.RECTANGLE)
tf = textbox(s, 0.95, 1.5, 7.2, 0.4)
para(tf, '一个内部技术培训 · 案例驱动', size=14, bold=True, color=CYAN, first=True, space_after=0)
tf = textbox(s, 0.95, 2.05, 7.2, 1.7)
para(tf, '从 0 到 1\n做企业级 AI 应用', size=42, bold=True, color=WHITE, first=True, space_after=4, line=1.12)
tf = textbox(s, 0.95, 3.95, 7.2, 0.8)
para(tf, '以「博思AI智能体」为蓝本：19 天，从一个能跑的 Demo，到一个能扛的生产系统',
     size=16, color=COVER_SOFT, first=True, space_after=0, line=1.3)
rect(s, 0.95, 5.15, 1.6, 0.03, fill=CYAN, shape=MSO_SHAPE.RECTANGLE)
tf = textbox(s, 0.95, 5.4, 7.2, 0.9)
para(tf, '6 微服务  ·  965 用例全绿  ·  54 项检查清单  ·  真实事故复盘',
     size=14, color=RGBColor(0x9F, 0xBC, 0xDC), first=True, space_after=0)
tf = textbox(s, 8.95, 1.75, 3.9, 0.4)
para(tf, '本场你会带走', size=13, bold=True, color=CYAN, first=True, space_after=0)
tf = textbox(s, 8.95, 2.25, 3.9, 3.6)
for i, t in enumerate(['一条从 Demo 到生产的完整路径图谱',
                       '“何时抽象 / 何时拆分”的判断方法',
                       '一份可自查的 54 项生产检查清单',
                       '4 个真实事故换来的教训']):
    para(tf, t, size=14, bullet='▸', color=WHITE, first=(i == 0), space_after=14, line=1.25)
notes(s, '开场：这不是架构宣讲，而是把一个真实 AI 应用从能跑到能扛的决策过程拆给你看。所有数字都来自真实项目。')
_page['n'] = 0
print('cover ok')

# ============ 2 培训目标 ============
s = slide()
frame(s, 'Why this talk', '为什么讲这个：AI 应用的最后一公里', 'Demo 到生产之间，隔着一整套工程决策')
bullets(s, 0.6, 1.85, 12.13, [
    ('大多数人做 AI 应用止步于「能跑」——演示很惊艳，一上线就露怯', 0),
    ('差的那一段不是算法能力，而是：可靠性、安全、成本、可观测性，以及出事之后能不能解释清楚', 0),
    ('本场用一个真实上线项目的 19 天演进做蓝本，把每个关键决策的「问题 → 方案 → 取舍 → 代价」摊开', 0),
], size=15, gap=14)
band(s, 5.2, '判断标准：Demo 证明「想法可行」；生产级证明「它不会在你睡觉的时候垮掉」', size=14)
notes(s, '强调：AI 编程工具解决了执行速度，但没解决工程判断。后者才是本课程重点。')

# ============ 3 产品是什么 ============
s = slide()
frame(s, 'Product', '先看终点：这是一个什么样的产品', '博思AI智能体 · 多模型协作与智能辩论平台')
rect(s, 0.6, 1.8, 12.13, 0.92, fill=PALE, adj=0.12)
tf = textbox(s, 0.9, 1.98, 11.5, 0.6)
para(tf, '一句话：让多个大模型像专家团队一样，同时参与对话、展开辩论、协作完成任务',
     size=17, bold=True, color=PRIMARY, first=True, space_after=0)
cards(s, items=[
    {'num': '01', 'title': '多模型对话', 'body': ['AI 伙伴群聊（多模型同房间）', '个人私密空间 + RAG 增强', '思考链实时透明展示'], 'color': CYAN},
    {'num': '02', 'title': '观点辩论场', 'body': ['标准辩论：3~6 模型 1~10 轮', '树状辩论：拆视角 → 并行 → 汇总', '反思可视化 / DAG 画布'], 'color': ACCENT},
    {'num': '03', 'title': '多模态与游戏', 'body': ['文生图 / 文生视频 / 图生 3D', '城堡围攻 / 乒乓 / 贪吃蛇', '统一入口，不再跨 App'], 'color': GREEN},
    {'num': '04', 'title': '知识与记忆', 'body': ['知识库 RAG（Milvus 向量）', '树洞三层记忆 短期→长期→画像', '知识脉络图可视化'], 'color': AMBER},
], top=2.95, height=2.55)
notes(s, '产品说明控制在 1-2 分钟，重点是后面的工程演进。')

# ============ 4 差异对比 ============
s = slide()
frame(s, 'Positioning', '它和「一个模型回答一个问题」有什么不同', '差异不在模型能力，而在编排与工程底线')
table(s, 0.6, 1.85, 12.13,
      ['维度', '传统 AI 产品', '本项目做法'],
      [['回答方式', '单一模型、单声道', '多模型群聊 + 三方辩论交叉验证'],
       ['推理过程', '黑盒，只给结论', '思考链实时流式透明展示'],
       ['参数策略', '所有问题同一套参数', '意图三层漏斗，分级处理'],
       ['能力边界', '聊天/绘画/视频分散在不同 App', '聊天、辩论、创作、游戏统一入口'],
       ['失败处理', '模型报错就卡住，用户只能刷新', '自动换模型 / 重试 / 降级，服务不中断']],
      col_widths=[1.6, 4.6, 5.9], fs=13, row_h=0.52, head_h=0.42)
notes(s, '这张表既是产品定位，也为后面的可靠性章节埋伏笔：最后一行的差距，正是 19 天里最花力气补的地方。')
print('batch1 ok')

# ============ 5 终态架构 ============
s = slide()
frame(s, 'Architecture', '终态技术架构：五层分工，职责清晰', '先看结果形态，再倒推每个决策是怎么来的')
layers = [
    ('表现层', 'React 18 + Vite · KeepAlive 常驻页 · SockJS/STOMP 流式 · 树状 DAG 画布 · 移动端适配', CYAN, 0.80),
    ('接入层   chat-web · 8081', 'JWT 认证 · 三层限流 · 内容安全 · WebSocket 广播 · CoreClient 负载均衡', ACCENT, 0.80),
    ('业务层   chat-core · 9090 主 / 9092 从（双实例）', '聊天 / 辩论 / 树洞编排 · 意图三层漏斗 · Agent 工具 · 思考链 · Multi-Agent 工作流', PRIMARY, 0.86),
    ('AI 能力层   chat-llm 9095 · games 8083 · media 8084', '多 Provider 策略 + 图执行引擎 + RAG + 知识图谱 + gRPC（REST / gRPC 双协议）', GREEN, 1.00),
    ('基础设施', 'MySQL · Redis · RabbitMQ · Nacos · Milvus · Prometheus + Alertmanager（12 规则 → 钉钉）', AMBER, 0.80),
]
y = 1.78
mid = 0.6 + 12.13 / 2
for i, (t, sub, color, hh) in enumerate(layers):
    rect(s, 0.6, y, 12.13, hh, fill=color, adj=0.07)
    tf = textbox(s, 0.95, y + 0.11, 11.6, 0.3)
    para(tf, t, size=15, bold=True, color=WHITE, first=True, space_after=0)
    tf = textbox(s, 0.95, y + 0.42, 11.6, hh - 0.5)
    para(tf, sub, size=12, color=RGBColor(0xE2, 0xEC, 0xF7), first=True, space_after=0, line=1.15)
    if i < len(layers) - 1:
        rect(s, mid - 0.16, y + hh + 0.02, 0.32, 0.2, fill=MUTED, shape=MSO_SHAPE.DOWN_ARROW)
        y += hh + 0.24
notes(s, '讲法：不要逐层念。重点指出「接入层扛并发、业务层做编排、AI 能力层对接模型」这条纵向分工线。')

# ============ 6 拆分依据 ============
s = slide()
frame(s, 'Decision 01', '为什么是 6 个服务：拆分要有依据', '按六个维度判断，而不是拆得越多越好')
table(s, 0.6, 1.85, 12.13,
      ['拆分维度', '判断', '结论'],
      [['变更频率', 'AI 编排逻辑改得最频繁，不该每次改动都拖累全站', 'chat-core'],
       ['资源边界', 'LLM 调用要独立扩容（token 成本 / 并发）', 'chat-llm'],
       ['故障域', '一个模块挂了不能全站瘫，游戏/多模态出问题不影响聊天', 'chat-games / chat-media'],
       ['并发压力', 'HTTP 与 WebSocket 接入要单独扛连接数和限流', 'chat-web'],
       ['未来增长', '新能力会持续加入，需要预留独立服务位', '保留扩展位'],
       ['机器资源', '有限内存决定了 JVM 实例数上限', '控制实例数']],
      col_widths=[2.2, 6.0, 3.9], fs=13, row_h=0.46, head_h=0.42)
notes(s, '最有迁移价值的一页：让听众拿自己的项目按这六个维度画一遍，常能立刻发现边界划错的地方。')

# ============ 7 五阶段总览 ============
s = slide()
frame(s, 'Roadmap', '19 天五阶段总览', '每一步都在解决上一阶段暴露出来的问题')
cards(s, items=[
    {'num': '阶段一 7/30-8/2', 'title': '先跑起来，但别裸奔', 'body': ['跑通最小闭环', 'Demo 阶段就预留分层骨架', '缓存/限流/降级位置先留好'], 'color': CYAN},
    {'num': '阶段二 8/3-8/6', 'title': '业务稳了才抽象', 'body': ['不为抽象而抽象', '功能不再变化时才建模', '8/5 树洞上线'], 'color': ACCENT},
    {'num': '阶段三 8/9-8/12', 'title': '单体太慢，拆微服务', 'body': ['按六维度拆出 6 个服务', '模型接入 SPI 化', 'LLM 独立服务化'], 'color': PRIMARY},
    {'num': '阶段四 8/13-8/15', 'title': '从能跑到扛得住', 'body': ['给 LLM 调用上生产底线', '性能 11s → 1-3s', '安全加固 + AK 事故复盘'], 'color': RED},
    {'num': '阶段五 8/16-8/17', 'title': '抽象可复用底座', 'body': ['深度思考做成用户开关', '抽离 llm-gateway', '通用能力脱离业务'], 'color': GREEN},
], top=1.85, height=3.05)
band(s, 5.25, '规律：每一阶段的触发点都是上一阶段暴露的真实痛点——不是计划出来的，是问题逼出来的', size=14)
notes(s, '这一页是全课目录：后面逐阶段展开，每阶段回答「什么问题 → 怎么做的 → 代价是什么」。')
print('batch2 ok')

# ============ 阶段一 ============
divider('第一阶段 · 7/30 - 8/2', '先让它跑起来，但别让它裸奔', 'Demo 阶段最不值钱的是功能，最值钱的是骨架', CYAN)
s = slide()
frame(s, 'Stage 1', '最小闭环 + 预留骨架', '第一天没有急着堆功能')
tf = textbox(s, 0.6, 1.8, 12.13, 0.4)
para(tf, '第一天跑通的最小闭环', size=15, bold=True, color=PRIMARY, first=True, space_after=6)
rect(s, 0.6, 2.25, 12.13, 0.72, fill=SOFT, line=LINE, adj=0.1)
tf = textbox(s, 0.95, 2.42, 11.5, 0.45)
para(tf, '前端页面  →  HTTP  →  后端编排  →  LLM  →  WebSocket 流式返回',
     size=17, bold=True, color=ACCENT, first=True, space_after=0)
tf = textbox(s, 0.6, 3.25, 12.13, 0.4)
para(tf, '但在单体里预先留好了三样东西', size=15, bold=True, color=PRIMARY, first=True, space_after=6)
cards(s, items=[
    {'title': '分层架构', 'body': ['Controller / Service / Repository 边界清晰', '哪怕初期实现很薄'], 'color': CYAN},
    {'title': '稳定性的位置', 'body': ['缓存、限流、降级的「位置」提前留好', '哪怕一开始是空实现'], 'color': ACCENT},
    {'title': '接入抽象', 'body': ['统一的模型接入抽象', '哪怕只有一个模型'], 'color': GREEN},
], top=3.75, height=1.75, tfs=14, fs=12)
band(s, 5.75, '认知：Demo 阶段不预留边界，需求爆发后再补叫「重构」；预留好了再长大，只叫「填充」', size=14)
notes(s, 'AI 时代的额外洞察：编码 AI 会顺着你已有的骨架走；给它一坨面条代码，它还你一坨更大的。')

# ============ 阶段二 ============
divider('第二阶段 · 8/3 - 8/6', '业务稳定了，才开始抽象', '过早抽象是债，过晚抽象是痛', ACCENT)
s = slide()
frame(s, 'Stage 2', '一个反直觉决策：不急着建领域模型', '抽象的最佳时机，不是「你觉得懂了」')
bullets(s, 0.6, 1.85, 12.13, [
    ('教程常说「先设计领域模型再写代码」，但业务形态没定型时，实体根本抽象不出来', 0),
    ('硬要抽象，只会得到一个「为了抽象而抽象」的四不像；一个接口 6、7 个参数时尤其明显', 0),
    ('实际做法：先用真实用户反馈收敛需求 → 等功能稳定、不再变化 → 再抽象成模型', 0),
    ('8/5 树洞上线是这个原则的第一个产物：跑了一段时间确认形态，才建表、才建模', 0),
], size=15, gap=13)
band(s, 5.35, '判定标准：抽象的最佳时机是「功能已经不再变化了」，而不是「我终于看懂了」', size=14, color=GREEN, fill=PALE_GREEN)
notes(s, '可追问听众：你项目里有没有那种「设计得很漂亮但一直在改」的模型？那多半是抽象早了。')
print('batch3 ok')

# ============ 阶段三 ============
divider('第三阶段 · 8/9 - 8/12', '单体太慢了：拆成微服务', '拆服务不是终点，而是新问题的起点', PRIMARY)
s = slide()
frame(s, 'Stage 3', '拆分的触发点，与拆完之后必须回答的三个问题', '这才是「拆微服务」真正的价值所在')
rect(s, 0.6, 1.8, 12.13, 0.86, fill=PALE_RED, adj=0.1)
tf = textbox(s, 0.95, 1.95, 11.5, 0.6)
para(tf, '触发点：功能越堆越多，单体启动要几十秒到一两分钟——改一个 bug 的窗口期，用户完全不可用',
     size=16, bold=True, color=RED, first=True, space_after=0, line=1.25)
cards(s, items=[
    {'num': '问 1', 'title': '挂了怎么办？', 'body': ['core 起双实例（9090 主 + 9092 从）', 'web 侧 CoreClient 轮询负载均衡'], 'color': PRIMARY},
    {'num': '问 2', 'title': '实例挂了，消息丢了吗？', 'body': ['RabbitMQ 持久化 + manual ack', '跨节点广播：任意实例都能把事件推给用户'], 'color': AMBER},
    {'num': '问 3', 'title': '怎么知道它挂了？', 'body': ['Prometheus + Alertmanager', '钉钉告警，主动发现而非等用户投诉'], 'color': CYAN},
], top=2.9, height=2.1)
band(s, 5.35, '价值：微服务逼你把「故障」当成一个必须回答的问题，而不是侥幸地假设它不会挂', size=14)
notes(s, '关键观念转折：拆服务的收益不是「看起来先进」，而是强迫团队把容错设计显式化。')

# ============ 阶段四 ============
divider('第四阶段 · 8/13 - 8/15', '从「能跑」到「扛得住」', '给 LLM 调用上生产级底线', RED)
s = slide()
frame(s, 'Stage 4 · 可靠性', '默认它会出错：四层调用保护', 'Resilience4j 组合拳，外加统一兜底响应')
steps = [('限流', 'RateLimiter', 'IP 600/min，用户 20/min，敏感接口 10/min', CYAN),
         ('熔断', 'CircuitBreaker', '上游持续失败就快速失败，不拖垮调用链', ACCENT),
         ('超时', 'TimeLimiter', '不无限等待，把长尾请求掐断', PRIMARY),
         ('重试', 'Retry', '仅幂等操作重试，配合指数退避', GREEN)]
sw = 2.75
for i, (zh, en, desc, color) in enumerate(steps):
    x = 0.6 + i * (sw + 0.35)
    rect(s, x, 2.0, sw, 1.5, fill=color, adj=0.07)
    tf = textbox(s, x + 0.2, 2.18, sw - 0.4, 0.35)
    para(tf, zh, size=20, bold=True, color=WHITE, first=True, space_after=0)
    tf = textbox(s, x + 0.2, 2.62, sw - 0.4, 0.3)
    para(tf, en, size=11, color=RGBColor(0xD6, 0xE6, 0xF5), first=True, space_after=0)
    tf = textbox(s, x + 0.2, 2.98, sw - 0.4, 0.5)
    para(tf, desc, size=11, color=WHITE, first=True, space_after=0, line=1.15)
    if i < 3:
        rect(s, x + sw + 0.07, 2.55, 0.21, 0.4, fill=MUTED, shape=MSO_SHAPE.RIGHT_ARROW)
band(s, 3.85, '外加两道兜底：多模型路由 + 故障转移；全局异常处理 + 统一错误响应（用户拿到可读反馈，而不是白屏）', size=14)
bullets(s, 0.6, 4.75, 12.13, [
    ('原则：可靠性靠「兜底」，不靠「假设不出错」', 0),
    ('为每个「可能会挂」的组件都预先设计恢复路径——包括 LLM 供应商本身', 0),
], size=15, gap=10)
notes(s, '提醒：四层顺序很重要。顺序错了会互相放大故障——例如先重试会让雪崩更严重。')
print('batch4 ok')

# ============ 12 消息可靠性 ============
s = slide()
frame(s, 'Stage 4 · 可靠性', '消息与任务的可靠性：错了不能丢，卡了能自愈', '异步链路最怕「静默失败」')
table(s, 0.6, 1.85, 12.13,
      ['机制', '解决什么问题', '本项目的做法'],
      [['持久化', '服务重启期间消息丢失', 'RabbitMQ 持久化 + manual ack'],
       ['死信重试', '消费失败的任务静默消失', 'DLX 死信队列 + 指数退避重投'],
       ['对账扫描', '卡在中间态的任务没人管', 'Reconciler 每 30s 对账（ZSet 索引）自愈'],
       ['并发原子性', '双实例下超发 / 计数泄漏', 'Redis Lua 脚本保证原子操作'],
       ['服务冗余', '单实例不可用的窗口', 'core 双实例，Web 侧负载均衡切换']],
      col_widths=[2.4, 4.4, 5.3], fs=13, row_h=0.5, head_h=0.42)
notes(s, '可结合后面「辩论记录卡在 debating」的真实故障讲：没有对账 + 没有状态回写，就变成用户眼里的永久卡死。')

# ============ 13 性能根因 ============
s = slide()
frame(s, 'Stage 4 · 性能', '11 秒 → 1-3 秒：一次完整的根因追踪', '用户只说「慢」，工程师要找出「为什么慢」')
tf = textbox(s, 0.6, 1.8, 12.13, 0.35)
para(tf, '现象：用户反馈响应要 11 秒。逐层排查后揪出两个隐蔽根因', size=15, bold=True, color=PRIMARY, first=True, space_after=4)
cards(s, items=[
    {'title': '根因一 · tool_call_id 缺失', 'body': ['工具调度时构造消息漏传该字段', 'DeepSeek 严格校验直接返回 400', '触发重试 → 熔断连锁反应', '单次工具调度耗时 11.5 秒'], 'color': RED},
    {'title': '根因二 · 豆包默认开启深度思考', 'body': ['doubao 系列默认输出 reasoning token', '实测 149 个 reasoning token', '首 token 延迟被拉到 4~18 秒', '修复：显式 thinking:disabled'], 'color': AMBER},
], top=2.3, height=2.3, fs=12.5, tfs=14)
band(s, 4.85, '共同点：都是「文档没明说、默认值坑人」的隐性坑——这类问题只有真实压测和逐层排查才能发现',
     size=14, color=RED, fill=PALE_RED)
bullets(s, 0.6, 5.6, 12.13, [
    ('结果：500 并发压测 P50 154ms；流式 token 30ms 窗口合并广播，MQ 消息量降一个数量级', 0),
    ('另有：有界队列 + 30min TTL 清理，避免无界增长导致 OOM', 0),
], size=14, gap=8)
notes(s, '可延展讨论：性能优化的正确顺序是「先测量 → 再定位单点 → 再动手」，而不是凭直觉改代码。')

# ============ 14 AK 泄露事故 ============
s = slide()
frame(s, 'Stage 4 · 安全', '一次真实的 AK 泄露事故复盘', '全课最诚实的一页：错误、代价、教训都被如实记录')
tf = textbox(s, 0.6, 1.78, 12.13, 0.35)
para(tf, '事故处理时间线', size=15, bold=True, color=PRIMARY, first=True, space_after=4)
cards(s, items=[
    {'title': '发生了什么', 'body': ['早期开发时把模型 API Key', '误提交进了 Git 仓库', '明文留存于历史提交中'], 'color': RED},
    {'title': '怎么发现的', 'body': ['依赖漏洞扫描阶段', '密钥检查工具 gitleaks 告警', '发现仓库残留明文 AK'], 'color': AMBER},
    {'title': '怎么处理的', 'body': ['立即吊销所有泄露 AK 并重建', '归档重建整个仓库（最彻底）', '代价：丢失 8/14 前 15 天 Git 历史'], 'color': PRIMARY},
    {'title': '之后怎么防', 'body': ['pre-commit 钩子 + CI gitleaks 双保险', '密钥入库存密文，环境变量仅兜底', '代码中绝不出现明文'], 'color': GREEN},
], top=2.25, height=2.15, fs=12.5, tfs=14)
band(s, 4.75, '教训：AK 泄露不是「会不会」，而是「什么时候」；一旦泄露必须吊销重建——只删那一行没用，历史提交里还在',
     size=14, color=RED, fill=PALE_RED)
bullets(s, 0.6, 5.55, 12.13, [
    ('代价是真实的：丢了 15 天 Git 历史，后来只能靠服务器日志一点点还原过程', 0),
], size=14, gap=6)
notes(s, '讲这一页时放慢语速。让听众意识到：安全措施往往是一次事故换来的，而代价可能不可逆。')
print('batch5 ok')

# ============ 15 安全清单 ============
s = slide()
frame(s, 'Stage 4 · 安全', '安全加固清单（精简版）', '12 项全覆盖，这里挑最容易被漏掉的')
table(s, 0.6, 1.85, 12.13,
      ['层面', '措施', '关键细节'],
      [['密钥管理', 'AK 加密落库 + 60s 热刷新', 'AES-256-GCM 密文，改 key 不重启，拖库不泄漏'],
       ['鉴权', 'JWT + 方法级 + WS 订阅级（fail-close）', 'JWT 密钥 <32 字节拒绝启动；管理面独立口令'],
       ['访问控制', '三级限流 + 防爆破 + 自动拉黑', '登录连错 5 次锁 15 分钟；60s 超 1000 次封 10 分钟'],
       ['输入防护', '上传白名单 + SSRF 防护 + SQL 白名单', '含 DNS 重绑定防护；危险词正则拦截'],
       ['内容安全', '输入 + 输出双检，fail-close', '检测服务挂了拒绝放行，而不是放行风险内容'],
       ['可审计', '日志脱敏 + 启动配置自检', 'JWT/签名 URL/密钥全遮蔽；占位符未替换打 ERROR']],
      col_widths=[2.0, 3.8, 6.3], fs=12.5, row_h=0.52, head_h=0.42)
notes(s, '强调 fail-close 的取舍哲学：宁可拒绝服务，也不放过风险内容。这是内容安全类产品的基本盘。')

# ============ 16 可观测 ============
s = slide()
frame(s, 'Stage 4 · 可观测', '看不见的系统 = 不可运维的系统', '监控要覆盖三层：系统、业务、链路')
cards(s, items=[
    {'title': '系统指标告警', 'body': ['Prometheus + Alertmanager + 钉钉', '内存 / CPU / 磁盘异常主动发现'], 'color': PRIMARY},
    {'title': '业务指标可见', 'body': ['AOP 切面采集业务指标', '命中率 / 降级率 / 成本可度量'], 'color': ACCENT},
    {'title': '告警分级', 'body': ['系统 8 条 + 业务 4 条，共 12 规则', '不把所有告警都标成紧急'], 'color': AMBER},
    {'title': '链路追踪', 'body': ['traceId 全链路透传', '结构化日志 + 分服务日志'], 'color': CYAN},
], top=1.85, height=1.85, fs=12.5, tfs=14)
band(s, 3.95, '踩过的版本坑：micrometer 必须锁定版本（1.11.6）——一次升级导致埋点静默失效，监控「看起来正常」却没数据',
     size=14, color=RED, fill=PALE_RED)
bullets(s, 0.6, 4.85, 12.13, [
    ('启动自检：密钥占位符、LLM api_key、表结构漂移——启动即校验，问题打 ERROR 日志，可被告警采集', 0),
    ('排障速度：从收到告警到定位根因以分钟计，靠的是结构化日志与统一 traceId', 0),
], size=14, gap=10)
notes(s, '可提问听众：如果现在某个接口静默降级了，多久能知道？如果答案是「等用户投诉」，这层就缺了。')

# ============ 17 质量门禁 ============
s = slide()
frame(s, 'Stage 4 · 质量', '质量门禁：让问题在进生产之前被拦住', '不是「测试通过」，而是「违规被自动拦截」')
rect(s, 0.6, 1.8, 12.13, 1.05, fill=PALE, adj=0.1)
tf = textbox(s, 0.95, 1.95, 11.5, 0.8)
para(tf, '全量 965 用例全绿', size=22, bold=True, color=PRIMARY, first=True, space_after=6)
para(tf, 'chat-common 277 · chat-core 257 · chat-llm 198 · chat-web 90 · chat-games 44 · chat-media 26',
     size=13, color=MUTED, space_after=0)
table(s, 0.6, 3.05, 12.13,
      ['门禁', '作用', '现状'],
      [['单元测试', '断言真实行为，不为覆盖率写测试', '965 用例，弱测试专项清零（反射/仅构造/空断言）'],
       ['集成测试', '真实链路走一遍（含 @SpringBootTest）', '关键链路覆盖：缓存 / 路由 / 熔断专项'],
       ['静态检查', '代码风格与常见缺陷自动拦截', 'Checkstyle 0 违规 + PMD 收敛 + SpotBugs'],
       ['依赖安全', '第三方依赖漏洞扫描', 'OWASP 扫描；Dependabot 12 项告警已清零'],
       ['CI 流水线', '提交即验证，违规不允许合入', 'ci.yml / deploy.yml / security 三套工作流']],
      col_widths=[2.0, 4.2, 5.9], fs=12.5, row_h=0.46, head_h=0.42)
notes(s, '「弱测试清零」值得展开：为覆盖率而写的测试，一个都挡不住回归，还会给人虚假的安全感。')
print('batch6 ok')

# ============ 阶段五 ============
divider('第五阶段 · 8/16 - 8/17', '抽象出可复用的底座', '通用能力一旦脱离业务，才能被复用', GREEN)
s = slide()
frame(s, 'Stage 5', '两个收尾动作：把权衡交给用户，把底座剥离出业务', '19 天结束时最重要的决定')
cards(s, items=[
    {'title': '深度思考，做成用户可配置', 'body': ['豆包 reasoning 又快又慢：开着深入但慢，关着快但浅', '没有替用户决定，而是在对话/群聊/辩论都加「深度思考」开关', '默认普通模式（快），需要深入时用户自己开'], 'color': CYAN},
    {'title': '抽离 llm-gateway', 'body': ['多模型路由、统一调用、流式、熔断、RAG、知识图谱', '这套能力与「聊天」毫无关系', '它是所有 AI 应用的通用底座 → 独立成项目'], 'color': GREEN},
], top=1.85, height=2.35, fs=13, tfs=15)
band(s, 4.55, '决策本质：把「快 vs 深」的权衡，交给真正做权衡的人——用户。工程师不该替用户做主观选择',
     size=14, color=GREEN, fill=PALE_GREEN)
bullets(s, 0.6, 5.4, 12.13, [
    ('通用能力从业务里剥离后，才能被复用、被传播——这是第二阶段「抽象时机」原则走到终局的样子', 0),
], size=14, gap=6)
notes(s, '呼应：阶段二说「等业务稳定再抽象」，阶段五就是这个原则走到底的结果——抽象到可以脱离本项目独立存在。')

# ============ 生产实战 ============
divider('实战复盘', '上线之后：四个真实事故与治理', '教科书上没有的那些坑，都是生产教的', AMBER)
s = slide()
frame(s, 'Incidents', '四个真实生产事故：现象 → 根因 → 治理', '每一个都来自真实运维记录，不是编的例子')
table(s, 0.6, 1.8, 12.13,
      ['事故', '根因', '治理动作与教训'],
      [['服务器 swap 吃满 69%',
        '双实例冗余 + Milvus/Nacos/MySQL 争抢 7.3G 内存',
        '按真实流量收缩为单实例，释放 1.4G；冗余要按流量定，别为「看起来高可用」压垮单机'],
       ['整机应用层挂死',
        'sshd 不吐 banner、端口能连不响应；Milvus 容器开机不自启',
        '控制台重启 + 建立启动清单：有状态依赖必须显式写进流程，不能靠记忆'],
       ['辩论记录永久停在 debating',
        '记录 ID 未从 web 传到 core，状态无法回写；前端又无超时兜底，WS 断连即卡死',
        '两端修：ID 透传 + 看门狗超时解锁；异步任务必须「状态可回写 + 前端有兜底」'],
       ['OSS 转存被拒（bucket ACL）',
        'AK 对该桶无 PutObject 权限，降级保留了 24 小时过期的源站 URL',
        '降级必须「过期可见」：否则用户历史图片会在一天后静默 404，数据无声消失']],
      col_widths=[2.9, 4.3, 5.9], fs=11.5, row_h=0.95, head_h=0.4)
notes(s, '这四个事故跨越多个子系统，共同点是：都不是代码写错了，而是「某种资源或状态没有人负责」。')
print('batch7 ok')

# ============ 方法论 ============
divider('方法论', '把经验固化成可自查的清单', '54 项 · 7 关 · 100 分制', ACCENT)
s = slide()
frame(s, 'Checklist', 'AI 应用从 Demo 到生产：54 项检查清单', '每项都对应一个「不做到就会出事」的真实教训')
table(s, 0.6, 1.8, 12.13,
      ['关卡', '子项数', '权重', '为什么它最重'],
      [['一、架构与拆分', '8', '15 分', '边界划错，后面每一步都在还债'],
       ['二、可靠性', '10', '20 分', 'LLM 天然不稳定，没兜底等于定时炸弹'],
       ['三、性能', '6', '15 分', '慢和无响应，对用户没有区别'],
       ['四、安全', '12', '20 分', 'AK 泄露、越权、注入，一次就可能致命'],
       ['五、可观测性', '6', '10 分', '看不见 = 不可运维 = 只能靠用户投诉'],
       ['六、测试与质量', '6', '10 分', '为覆盖率写的测试挡不住任何回归'],
       ['七、文档与留痕', '6', '10 分', '没有 ADR，三个月后自己都看不懂当初为什么']],
      col_widths=[2.6, 1.2, 1.4, 7.2], fs=13, row_h=0.44, head_h=0.42)
band(s, 5.85, '红线规则：标记红线的项目若未做到，该关直接记 0 分——红线不是加分项，是一票否决',
     size=14, color=RED, fill=PALE_RED)
notes(s, '建议让听众现场给自己的项目打分，往往能立刻暴露意料之外的短板。')

s = slide()
frame(s, 'Self-check', '评分怎么读：四个成熟度等级', '分数不是目的，先补红线才是')
cards(s, items=[
    {'num': '≥ 85 分', 'title': '生产级成熟', 'body': ['架构/可靠/安全基本闭环', '可以放心对外上线'], 'color': GREEN},
    {'num': '70-84 分', 'title': '生产级达标', 'body': ['可以上线', '前提：红线项必须全达标'], 'color': CYAN},
    {'num': '55-69 分', 'title': '接近生产级', 'body': ['补齐红线项之后再上线', '其余按优先级逐步补'], 'color': AMBER},
    {'num': '< 55 分', 'title': '仍是 Demo', 'body': ['先补架构与可靠性', '不要急着上生产'], 'color': RED},
], top=1.9, height=2.0, fs=12.5, tfs=15)
tf = textbox(s, 0.6, 4.25, 12.13, 0.35)
para(tf, '计分方式', size=15, bold=True, color=PRIMARY, first=True, space_after=4)
rect(s, 0.6, 4.65, 12.13, 0.6, fill=SOFT, line=LINE, adj=0.12)
tf = textbox(s, 0.95, 4.79, 11.5, 0.4)
para(tf, '每关得分 = 该关权重 ×（达标项数 + 部分达标项数 × 0.5）/ 该关子项数；红线项未做则该关记 0',
     size=14, color=TEXT, first=True, space_after=0)
bullets(s, 0.6, 5.5, 12.13, [
    ('用法建议：不要追求一次满分。按「会不会出大事」排序，先把红线项补完，再谈优化', 0),
], size=14, gap=6)
notes(s, '工具属性的一页：可直接把 AI-ENGINEERING-CHECKLIST.md 发给学员做课后作业。')
print('batch8 ok')

# ============ AI 时代还得亲手做的事 ============
s = slide()
frame(s, 'Takeaway', 'AI 时代，什么还得工程师亲手做', '19 天全程 AI 辅助编码，但以下四件事它替代不了')
cards(s, items=[
    {'num': '01', 'title': '抽象能力', 'body': ['什么时候该抽象', '抽象到什么粒度', 'AI 给不了答案'], 'color': CYAN},
    {'num': '02', 'title': '权衡能力', 'body': ['快 vs 深、安全 vs 可用', '取舍由业务场景决定', '不是技术最优解'], 'color': ACCENT},
    {'num': '03', 'title': '根因追踪', 'body': ['11 秒的慢、缓存不命中、AK 泄露', 'AI 不会主动告诉你', '默认值在哪儿坑人'], 'color': AMBER},
    {'num': '04', 'title': '工程判断', 'body': ['拆几个服务、起几个实例', '预留多少扩展空间', '取决于真实约束'], 'color': GREEN},
], top=1.95, height=2.3, fs=12.5, tfs=15)
band(s, 4.6, '结论：AI 放大了执行速度，但方向盘一直是人自己', size=17, color=WHITE, fill=PRIMARY, height=0.8)
notes(s, '收尾升华：不要神化也不要贬低 AI 编程工具。它改变的是打字速度，没改变「工程质量由判断力决定」。')

# ============ 行动建议 ============
s = slide()
frame(s, 'Action', '下周就能做的 7 件事', '不用重构，先从这些低投入高回报的动作开始')
for i, t in enumerate([
    '按「变更频率 / 资源边界 / 故障域」三个维度，重新画一遍你的服务边界',
    '给所有外部调用补齐五件套：限流 → 熔断 → 超时 → 重试 → 降级',
    '上 pre-commit + CI 密钥扫描；密钥进库并支持热更新（改 key 不重启）',
    '给异步任务补三件事：状态可回写 + 前端超时兜底 + 定时对账扫描',
    '定义 4-6 个业务指标（命中率 / 降级率 / 成本），至少配 1 条业务告警',
    '把下一个关键决策写成 1 条 ADR（一个文件，5 分钟，未来的你会感谢自己）',
    '用 54 项清单给自己的项目打分，先补红线项，其余按「会不会出大事」排优先级',
]):
    y = 1.85 + i * 0.68
    rect(s, 0.6, y, 0.42, 0.42, fill=ACCENT, adj=0.15)
    tf = textbox(s, 0.62, y + 0.04, 0.4, 0.35)
    para(tf, str(i + 1), size=14, bold=True, color=WHITE, align=PP_ALIGN.CENTER, first=True, space_after=0)
    tf = textbox(s, 1.2, y + 0.03, 11.5, 0.4)
    para(tf, t, size=14.5, color=TEXT, first=True, space_after=0)
notes(s, '这页是作业清单：建议让学员挑 2-3 条下周落地，下次复盘时对答案。')

# ============ 资源 ============
s = slide()
frame(s, 'Resources', '延伸阅读：本项目的全部素材都在仓库里', '所有数据与案例均可溯源，欢迎拿去改造自用')
table(s, 0.6, 1.85, 12.13,
      ['资源', '位置', '你能拿它做什么'],
      [['AI 工程检查清单', 'AI-ENGINEERING-CHECKLIST.md', '54 项自查，直接套用到你的项目'],
       ['19 天演进实录', 'docs/08-项目复盘/', '按「问题→方案→取舍→代价」读原始决策'],
       ['架构决策记录 ADR', 'docs/01-架构设计/ADR-架构决策记录.md', '25+ 条决策，学习如何写 ADR'],
       ['接口与数据字典', 'docs/02-API与数据库/', '对照自己的 API 设计习惯'],
       ['运维与故障手册', 'docs/03-运维部署/', '部署手册、排障手册、CI/CD 配置'],
       ['监控告警资产', 'prometheus-alert-rules.yml 等', '12 条告警规则可直接复用']],
      col_widths=[3.0, 4.2, 4.9], fs=13, row_h=0.52, head_h=0.42)
band(s, 5.9, '最后一句：把踩过的坑写下来，比把「我很厉害」写下来，更有价值', size=15, color=ACCENT)
notes(s, '结课：工程文化的落脚点是「可传承」——能被别人复用的文档，才算真正完成了工程闭环。')

out_dir = os.path.dirname(os.path.abspath(__file__))
os.makedirs(out_dir, exist_ok=True)
out = os.path.join(out_dir, '从0到1企业级AI应用培训.pptx')
prs.save(out)
size_mb = os.path.getsize(out) / 1024 / 1024
print('已生成:', out)
print('页数:', len(prs.slides._sldIdLst))
print('大小: %.2f MB' % size_mb)
