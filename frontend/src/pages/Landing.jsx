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
    if (saved && savedTime && (now - parseInt(savedTime)) < 60000) {
      return parseInt(saved)
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
    axios.post('/api/v1/monitor/record').catch(() => {})
    axios.get('/api/v1/monitor/total-usage').then(res => {
      setTotalUsage(res.data?.totalUsage || 0)
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
        client.publish({
          destination: '/app/online.register',
          body: JSON.stringify({ userId: landingId, name: '访客', page: 'landing' })
        })
      }
    })
    stompRef.current = client
    client.activate()
    return () => {
      client.publish({
        destination: '/app/online.unregister',
        body: JSON.stringify({ userId: landingId, page: 'landing' })
      })
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
          <a href="#product-intro" className="btn-outline">产品简介</a>
          <Link to="/debate" className="btn-outline">AI博弈</Link>
          <Link to="/" className="btn-outline">社交AI对话</Link>
          <Link to="/personal" className="btn-outline">个人对话</Link>
          <Link to="/graph" className="btn-outline">问答图谱</Link>
          <a href="#arch" className="btn-outline">了解架构</a>
          <Link to="/about" className="btn-outline">制作人简介</Link>
        </div>

        {/* Product Intro */}
        <section className="product-intro" id="product-intro">
          <h2 className="section-title">产品简介</h2>
          <p className="product-lead">
            博思（BoSi）是一个融合多模型AI能力的智能对话平台，核心功能为AI博弈——多个大模型同时针对你的问题给出答案，并展开互相讨论与辩论，最终整合输出最优解答，帮你高效解决生活或工作中的每一个问题。
          </p>
          <div className="feature-grid">
            <Link to="/debate" className="feature-card">
              <div className="feature-icon">🤖</div>
              <h3>AI博弈</h3>
              <p>三大模型围绕你提的问题展开多轮讨论，各抒己见给出阶段结论、互相反驳，最终整合生成结论</p>
            </Link>
            <Link to="/" className="feature-card">
              <div className="feature-icon">💬</div>
              <h3>社交AI对话</h3>
              <p>公共论坛式AI对话，所有用户可实时查看提问与回答，支持多人在线互动与思维碰撞</p>
            </Link>
            <Link to="/personal" className="feature-card">
              <div className="feature-icon">🔒</div>
              <h3>个人对话空间</h3>
              <p>完全私密的AI助手对话，内容不广播、不展示、不入图谱，只属于你自己的智能伙伴</p>
            </Link>
            <Link to="/media" className="feature-card">
              <div className="feature-icon">🎨</div>
              <h3>图片与视频生成</h3>
              <p>接入通义千问多模态模型，输入文字描述即可生成高质量图片与视频内容</p>
            </Link>
            <Link to="/graph" className="feature-card">
              <div className="feature-icon">🌐</div>
              <h3>问答图谱</h3>
              <p>3D力导向图可视化展示历史问答间的语义关联，支持搜索高亮与节点交互探索</p>
            </Link>
            <Link to="/history" className="feature-card">
              <div className="feature-icon">📋</div>
              <h3>问答列表</h3>
              <p>全量结构化浏览历史问答记录，支持快速检索与回顾每一轮精彩对话</p>
            </Link>
          </div>
        </section>

      </section>

      {/* Features */}
      <section className="features">
        <h2 className="section-title">核心能力</h2>
        <div className="feature-grid">
          <div className="feature-card">
            <div className="feature-icon">🧠</div>
            <h3>意图识别</h3>
            <p>Python Agent 驱动的自然语言理解，精准捕捉用户意图</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">🔄</div>
            <h3>任务规划</h3>
            <p>大模型主导的循环决策机制，自动拆解复杂任务</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">⚡</div>
            <h3>工具编排</h3>
            <p>MCP 协议标准化调用，跨语言能力统一接入</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">🛡️</div>
            <h3>稳态底座</h3>
            <p>Spring Boot 承载高并发业务逻辑，保障数据安全</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">🔌</div>
            <h3>弹性扩展</h3>
            <p>Docker / K8s 统一编排，支持异构 AI 服务接入</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">📡</div>
            <h3>链路追踪</h3>
            <p>SkyWalking 打通双栈监控，异常快速定位</p>
          </div>
        </div>
      </section>

      {/* Architecture */}
      <section className="arch" id="arch">
        <h2 className="section-title">应用架构 · 中间件架构 · 基础设施与AI融合架构</h2>
        <div className="arch-diagram">
          <div className="arch-layer arch-frontend">
            <div className="arch-label">前端层 · Frontend</div>
            <div className="arch-boxes">
              <div className="arch-box">React SPA</div>
              <div className="arch-box">WebSocket</div>
              <div className="arch-box">STOMP 消息</div>
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
              <div className="arch-box">Spring Boot Gateway</div>
              <div className="arch-box">JWT 鉴权</div>
              <div className="arch-box">路由分发</div>
              <div className="arch-box">SkyWalking 链路追踪</div>
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
        <span>Powered by Application × Middleware × Infrastructure × AI Architecture</span>
      </footer>
    </div>
  )
}
