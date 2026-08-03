// frontend/src/pages/Landing.jsx
import React, { useEffect, useState, useRef } from 'react'
import { Link } from 'react-router-dom'
import axios from 'axios'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

export default function Landing() {
  const animRef = useRef(null)
  const lastTimeRef = useRef(null)
  const [baseCount, setBaseCount] = useState(() => {
    const saved = localStorage.getItem('landing_base_count')
    const savedTime = localStorage.getItem('landing_base_time')
    const now = Date.now()
    if (saved && savedTime && (now - parseInt(savedTime, 10)) < 60000) {
      return parseInt(saved, 10)
    }
    const val = Math.floor(Math.random() * 201)
    localStorage.setItem('landing_base_count', String(val))
    localStorage.setItem('landing_base_time', String(now))
    return val
  })
  const [realUsers, setRealUsers] = useState(0)
  const [totalUsage, setTotalUsage] = useState(0)
  const stompRef = useRef(null)

  useEffect(() => {
    const timer = setInterval(() => {
      const val = Math.floor(Math.random() * 201)
      setBaseCount(val)
      localStorage.setItem('landing_base_count', String(val))
      localStorage.setItem('landing_base_time', String(Date.now()))
    }, 60000)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    axios.get('/api/v1/monitor/total-usage').then(res => {
      setTotalUsage(res.data?.totalUsage || 0)
    }).catch(() => {})
    axios.get('/api/v1/messages/online-count', { params: { page: 'landing' } }).then(res => {
      setRealUsers(res.data?.count || 0)
    }).catch(() => {})
    let landingId = localStorage.getItem('landing_visitor_id')
    if (!landingId) {
      landingId = 'landing-' + Math.floor(Math.random() * 100000)
      localStorage.setItem('landing_visitor_id', landingId)
    }
    const sock = new SockJS('/ws/chat?userId=' + landingId)
    const client = new Client({
      webSocketFactory: () => sock,
      debug: () => {},
      onConnect: () => {
        client.subscribe('/topic/online-count/landing', (msg) => {
          try {
            const payload = JSON.parse(msg.body)
            setRealUsers(payload.count || 0)
          } catch {}
        })
      }
    })
    stompRef.current = client
    client.activate()
    return () => {
      client.deactivate()
    }
  }, [])

  const onlineCount = baseCount + realUsers

  return (
    <div className="landing">
      <section className="hero">
        <h1 className="hero-title">博思AI智能体</h1>
        <div className="hero-stats-row">
          <div className="hero-online-badge">
            <span className="online-dot"></span>
            {onlineCount} 人在线
          </div>
          <div className="hero-usage-badge">
            <span className="usage-icon">📈</span>
            累计使用 {totalUsage >= 10000 ? (totalUsage / 10000).toFixed(1) + '万' : totalUsage.toLocaleString()} 次
          </div>
        </div>
        <div className="hero-actions">
          <a href="#product-intro" className="btn-outline">功能简介</a>
          <Link to="/debate" className="btn-outline">观点辩论场</Link>
          <Link to="/" className="btn-outline">AI伙伴群聊</Link>
          <Link to="/games" className="btn-outline">AI多人游戏</Link>
          <Link to="/personal" className="btn-outline">个人对话空间</Link>
          <Link to="/graph" className="btn-outline">知识脉络图</Link>
          <a href="#arch" className="btn-outline">了解架构</a>
          <Link to="/about" className="btn-outline">制作人简介</Link>
        </div>

        {/* Product Intro */}
        <section className="product-intro" id="product-intro">
          <h2 className="section-title">功能简介</h2>
          <p className="product-lead">
            打破人机边界，融合真人社交与AI智慧，打造懂你、助你的全能数字伙伴。
          </p>
          <div className="feature-grid">
            <Link to="/debate" className="feature-card">
              <div className="feature-icon">🤖</div>
              <h3>观点辩论场</h3>
              <p>让三位AI专家为你展开辩论，在思想交锋中，帮你获得更全面、更深入的结论。</p>
            </Link>
            <Link to="/games" className="feature-card">
              <div className="feature-icon">🎮</div>
              <h3>AI多人游戏</h3>
              <p>和真人玩家、AI模型同场竞技，在蛇王争霸、城池争夺战与AI乒乓球中体验更有代入感的多人对抗乐趣。</p>
            </Link>
            <Link to="/personal" className="feature-card">
              <div className="feature-icon">🔒</div>
              <h3>个人对话空间</h3>
              <p>你的专属私密空间，安全归档所有灵感与深度探讨，让AI成为你成长的长期伙伴。</p>
            </Link>
            <Link to="/" className="feature-card">
              <div className="feature-icon">💬</div>
              <h3>AI 伙伴群聊</h3>
              <p>随时拉上AI伙伴加入你的群聊，它既是智能助手，也是懂气氛的聊天搭子。</p>
            </Link>
            <Link to="/media" className="feature-card">
              <div className="feature-icon">🎨</div>
              <h3>文生视频/图</h3>
              <p>一句提示词，秒级生成电影级大片或短视频，低成本实现从"脑洞"到"现实"。</p>
            </Link>
            <Link to="/graph" className="feature-card">
              <div className="feature-icon">🌐</div>
              <h3>知识脉络图</h3>
              <p>将零散的知识点连接成网，帮你一眼看清问题的来龙去脉和核心关联。</p>
            </Link>
            <Link to="/history" className="feature-card">
              <div className="feature-icon">📋</div>
              <h3>问答足迹</h3>
              <p>集中管理你的提问与探索，支持快速检索与二次编辑，让过往思考不被遗忘。</p>
            </Link>
          </div>
        </section>

      </section>

      {/* Features */}
      <section className="features">
        <h2 className="section-title">核心能力</h2>
        <p className="product-lead">
          从意图理解到任务执行，从安全守护到弹性扩展，六大核心能力构建全能数字伙伴。
        </p>
        <div className="feature-grid">
          <div className="feature-card">
            <div className="feature-icon">🧠</div>
            <h3>懂你所想</h3>
            <p>无论是规划行程、分析资料还是创作内容，它都能精准理解你的意图，自动拆解步骤并调用工具，让你专注于结果本身。</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">🔄</div>
            <h3>自动拆解</h3>
            <p>无论是写策划还是做攻略，只需一句话，我就能为你制定详细的行动路线图，按部就班，高效交付。</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">⚡</div>
            <h3>随时调用</h3>
            <p>告别在不同AI软件间来回切换的烦恼。我能自动调动各种外部服务，将复杂的多步操作化繁为简，让体验如丝般顺滑。</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">🛡️</div>
            <h3>安全可靠</h3>
            <p>采用金融级安全架构，全方位守护你的数据隐私与系统稳定，让你每一次使用都安心无忧。</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">🔌</div>
            <h3>轻松扛住</h3>
            <p>无论是日常使用还是突发热点，强大的底层算力都能瞬间调动资源，为你护航，让每一次对话都如丝般顺滑。</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">📡</div>
            <h3>全程透明</h3>
            <p>遇到复杂问题，AI会自动记录它的分析路径与推理过程。不仅给你最终答案，更让你看懂得出答案的逻辑。</p>
          </div>
        </div>
      </section>

      {/* Architecture */}
      <section className="arch" id="arch">
        <h2 className="section-title">全栈融合架构</h2>
        <p className="product-lead">
          应用架构、中间件架构、基础设施与AI架构深度融合，构建具备感知、规划、执行与反思能力的企业级智能体。
        </p>
        <div className="arch-diagram">
          <div className="arch-layer arch-frontend">
            <div className="arch-label">前端层 · Frontend</div>
            <div className="arch-boxes">
              <div className="arch-box">
                <div className="box-title">React SPA</div>
                <div className="box-sub">单页应用 · 组件化</div>
              </div>
              <div className="arch-box">
                <div className="box-title">WebSocket</div>
                <div className="box-sub">实时双向通信</div>
              </div>
              <div className="arch-box">
                <div className="box-title">STOMP 消息</div>
                <div className="box-sub">消息协议 · 订阅推送</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Vite 构建</div>
                <div className="box-sub">极速打包 · 热更新</div>
              </div>
              <div className="arch-box">
                <div className="box-title">React Router</div>
                <div className="box-sub">前端路由 · 导航</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Axios 请求</div>
                <div className="box-sub">HTTP 客户端 · 拦截器</div>
              </div>
            </div>
          </div>
          <div className="arch-arrow">
            <div className="flow-down">
              <div className="flow-track"><span></span><span></span><span></span></div>
              <div className="flow-head-down"></div>
            </div>
            <div className="flow-label">请求</div>
            <div className="flow-up">
              <div className="flow-head-up"></div>
              <div className="flow-track flow-up-track"><span></span><span></span><span></span></div>
            </div>
          </div>
          <div className="arch-layer arch-gateway">
            <div className="arch-label">网关层 · Gateway</div>
            <div className="arch-boxes">
              <div className="arch-box">
                <div className="box-title">Spring Boot Gateway</div>
                <div className="box-sub">API 网关 · 请求路由</div>
              </div>
              <div className="arch-box">
                <div className="box-title">JWT 鉴权</div>
                <div className="box-sub">令牌认证 · 安全校验</div>
              </div>
              <div className="arch-box">
                <div className="box-title">路由分发</div>
                <div className="box-sub">负载均衡 · 流量控制</div>
              </div>
              <div className="arch-box">
                <div className="box-title">SkyWalking 链路追踪</div>
                <div className="box-sub">性能监控 · 调用链分析</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Nginx 反向代理</div>
                <div className="box-sub">静态资源 · 请求转发</div>
              </div>
              <div className="arch-box">
                <div className="box-title">负载均衡</div>
                <div className="box-sub">多实例 · 高可用</div>
              </div>
              <div className="arch-box">
                <div className="box-title">API 限流</div>
                <div className="box-sub">频率控制 · 防刷保护</div>
              </div>
            </div>
          </div>
          <div className="arch-arrow">
            <div className="flow-down">
              <div className="flow-track"><span></span><span></span><span></span></div>
              <div className="flow-head-down"></div>
            </div>
            <div className="flow-label">路由</div>
            <div className="flow-up">
              <div className="flow-head-up"></div>
              <div className="flow-track flow-up-track"><span></span><span></span><span></span></div>
            </div>
          </div>
          <div className="arch-layer arch-security">
            <div className="arch-label">安全层 · Security</div>
            <div className="arch-boxes">
              <div className="arch-box">
                <div className="box-title">数据安全</div>
                <div className="box-sub">加密传输 · 访问控制</div>
              </div>
              <div className="arch-box">
                <div className="box-title">数据脱敏</div>
                <div className="box-sub">敏感信息自动遮蔽</div>
              </div>
              <div className="arch-box">
                <div className="box-title">日志审计</div>
                <div className="box-sub">全链路操作追溯</div>
              </div>
              <div className="arch-box">
                <div className="box-title">内容安全</div>
                <div className="box-sub">敏感词过滤 · 合规检测</div>
              </div>
              <div className="arch-box">
                <div className="box-title">限流防护</div>
                <div className="box-sub">频率控制 · 熔断降级</div>
              </div>
            </div>
          </div>
          <div className="arch-arrow">
            <div className="flow-down">
              <div className="flow-track"><span></span><span></span><span></span></div>
              <div className="flow-head-down"></div>
            </div>
            <div className="flow-label">鉴权</div>
            <div className="flow-up">
              <div className="flow-head-up"></div>
              <div className="flow-track flow-up-track"><span></span><span></span><span></span></div>
            </div>
          </div>
          <div className="arch-layer arch-dual">
            <div className="arch-label">应用核心 · Application Core</div>
            <div className="arch-boxes arch-dual-boxes">
              <div className="arch-box box-java">
                <div className="box-title">Java 稳态层</div>
                <div className="box-sub">用户 · 权限 · 消息 · 事务</div>
              </div>
              <div className="arch-connector">
                <div className="connector-line"></div>
                <div className="connector-label">REST / gRPC / MCP</div>
                <div className="connector-line"></div>
              </div>
              <div className="arch-box box-python">
                <div className="box-title">Python 敏态层</div>
                <div className="box-sub">意图 · 规划 · 检索 · 反思</div>
              </div>
            </div>
          </div>
          <div className="arch-arrow">
            <div className="flow-down">
              <div className="flow-track"><span></span><span></span><span></span></div>
              <div className="flow-head-down"></div>
            </div>
            <div className="flow-label">调用</div>
            <div className="flow-up">
              <div className="flow-head-up"></div>
              <div className="flow-track flow-up-track"><span></span><span></span><span></span></div>
            </div>
          </div>
          <div className="arch-layer arch-middleware">
            <div className="arch-label">中间件层 · Middleware</div>
            <div className="arch-boxes">
              <div className="arch-box">
                <div className="box-title">RabbitMQ</div>
                <div className="box-sub">业务消息 · 延迟队列</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Kafka</div>
                <div className="box-sub">高吞吐日志 · 事件流</div>
              </div>
              <div className="arch-box">
                <div className="box-title">异步解耦</div>
                <div className="box-sub">削峰填谷 · 高可用</div>
              </div>
              <div className="arch-box">
                <div className="box-title">消息持久化</div>
                <div className="box-sub">可靠投递 · 幂等消费</div>
              </div>
              <div className="arch-box">
                <div className="box-title">死信队列</div>
                <div className="box-sub">失败重试 · 异常处理</div>
              </div>
            </div>
          </div>
          <div className="arch-arrow">
            <div className="flow-down">
              <div className="flow-track"><span></span><span></span><span></span></div>
              <div className="flow-head-down"></div>
            </div>
            <div className="flow-label">写入</div>
            <div className="flow-up">
              <div className="flow-head-up"></div>
              <div className="flow-track flow-up-track"><span></span><span></span><span></span></div>
            </div>
          </div>
          <div className="arch-layer arch-bigdata">
            <div className="arch-label">大数据层 · Big Data</div>
            <div className="arch-boxes">
              <div className="arch-box">
                <div className="box-title">Spark</div>
                <div className="box-sub">批处理 · ETL · ML</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Flink</div>
                <div className="box-sub">实时流计算 · CEP</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Hive MetaStore</div>
                <div className="box-sub">统一元数据管理</div>
              </div>
              <div className="arch-box">
                <div className="box-title">流批一体</div>
                <div className="box-sub">Spark + Flink 协同</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Airflow</div>
                <div className="box-sub">任务调度 · 工作流编排</div>
              </div>
              <div className="arch-box">
                <div className="box-title">ClickHouse</div>
                <div className="box-sub">OLAP 分析 · 实时查询</div>
              </div>
            </div>
          </div>
          <div className="arch-arrow">
            <div className="flow-down">
              <div className="flow-track"><span></span><span></span><span></span></div>
              <div className="flow-head-down"></div>
            </div>
            <div className="flow-label">存储</div>
            <div className="flow-up">
              <div className="flow-head-up"></div>
              <div className="flow-track flow-up-track"><span></span><span></span><span></span></div>
            </div>
          </div>
          <div className="arch-layer arch-storage">
            <div className="arch-label">存储层 · Storage</div>
            <div className="arch-boxes">
              <div className="arch-box">
                <div className="box-title">MySQL</div>
                <div className="box-sub">业务关系数据</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Redis</div>
                <div className="box-sub">缓存 · 会话 · 分布式锁</div>
              </div>
              <div className="arch-box">
                <div className="box-title">HDFS</div>
                <div className="box-sub">分布式文件存储</div>
              </div>
              <div className="arch-box">
                <div className="box-title">HBase</div>
                <div className="box-sub">列式海量数据</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Elasticsearch</div>
                <div className="box-sub">全文检索 · 日志分析</div>
              </div>
              <div className="arch-box">
                <div className="box-title">MinIO</div>
                <div className="box-sub">对象存储 · 文件管理</div>
              </div>
            </div>
          </div>
          <div className="arch-arrow">
            <div className="flow-down">
              <div className="flow-track"><span></span><span></span><span></span></div>
              <div className="flow-head-down"></div>
            </div>
            <div className="flow-label">部署</div>
            <div className="flow-up">
              <div className="flow-head-up"></div>
              <div className="flow-track flow-up-track"><span></span><span></span><span></span></div>
            </div>
          </div>
          <div className="arch-layer arch-infra">
            <div className="arch-label">基础设施层 · Infrastructure</div>
            <div className="arch-boxes">
              <div className="arch-box">
                <div className="box-title">Docker</div>
                <div className="box-sub">容器化部署</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Kubernetes</div>
                <div className="box-sub">弹性编排 · 自动伸缩</div>
              </div>
              <div className="arch-box">
                <div className="box-title">CI/CD</div>
                <div className="box-sub">持续集成 · 持续交付</div>
              </div>
              <div className="arch-box">
                <div className="box-title">监控告警</div>
                <div className="box-sub">Prometheus · Grafana</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Terraform</div>
                <div className="box-sub">基础设施即代码</div>
              </div>
              <div className="arch-box">
                <div className="box-title">Helm</div>
                <div className="box-sub">K8s 包管理 · 版本控制</div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Disclaimer */}
      <div className="landing-disclaimer">
        <p>⚠️ 用户须知：请在使用本平台时遵守国家法律法规，文明发言，共同维护良好的网络环境。</p>
      </div>

      {/* Footer */}
      <footer className="landing-footer">
        <span>博思AI智能体 · 全栈融合架构驱动</span>
      </footer>
    </div>
  )
}
