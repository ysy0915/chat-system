// frontend/src/pages/Landing.jsx
import { useEffect, useState, useRef } from 'react'
import { Link } from 'react-router-dom'
import apiClient from '../config/http'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { useLanguage } from '../i18n/LanguageContext'

export default function Landing() {
  const { t, lang } = useLanguage()
  const [onlineCount, setOnlineCount] = useState(0)
  const [totalUsage, setTotalUsage] = useState(0)
  const stompRef = useRef(null)

  useEffect(() => {
    // 获取 1 小时内活跃人数
    apiClient.get('/api/v1/messages/online-count', { params: { page: 'landing' } })
      .then(res => { if (res.data?.hourlyActive != null) setOnlineCount(res.data.hourlyActive) })
      .catch(() => {})

    // 订阅 WebSocket，接收定时刷新的在线数
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
            if (payload.hourlyActive != null) setOnlineCount(payload.hourlyActive)
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

  useEffect(() => {
    apiClient.get('/api/v1/monitor/total-usage').then(res => {
      setTotalUsage(res.data?.totalUsage || 0)
    }).catch(() => {})
  }, [])

  return (
    <div className="landing">
      <section className="hero">
        <h1 className="hero-title">{t('landing.heroTitle')}</h1>
        <div className="hero-stats-row">
          <div className="hero-online-badge">
            <span className="online-dot"></span>
            {t('landing.hourlyActive', { count: onlineCount })}
          </div>
          <div className="hero-usage-badge">
            <span className="usage-icon">📈</span>
            {t('landing.totalUsage', { count: totalUsage >= 10000 ? (totalUsage / 10000).toFixed(1) + (lang === 'zh' ? '万' : '0K') : totalUsage.toLocaleString() })}
          </div>
        </div>


        {/* Product Intro */}
        <section className="product-intro" id="product-intro">
          <h2 className="section-title">{t('landing.featuresTitle')}</h2>
          <p className="product-lead">
            {t('landing.productLead')}
          </p>
          <p className="product-tip">{t('landing.productTip')}</p>
          <div className="feature-grid">
            <Link to="/debate" className="feature-card">
              <div className="feature-icon">🤖</div>
              <h3>{t('landing.f.debate.title')}</h3>
              <p>{t('landing.f.debate.desc')}</p>
            </Link>
            <Link to="/graph" className="feature-card">
              <div className="feature-icon">🌐</div>
              <h3>{t('landing.f.graph.title')}</h3>
              <p>{t('landing.f.graph.desc')}</p>
            </Link>
            <Link to="/personal" className="feature-card">
              <div className="feature-icon">🔒</div>
              <h3>{t('landing.f.personal.title')}</h3>
              <p>{t('landing.f.personal.desc')}</p>
            </Link>
            <Link to="/treehole" className="feature-card">
              <div className="feature-icon">🌳</div>
              <h3>{t('landing.f.treehole.title')}</h3>
              <p>{t('landing.f.treehole.desc')}</p>
            </Link>
            <Link to="/" className="feature-card">
              <div className="feature-icon">💬</div>
              <h3>{t('landing.f.chat.title')}</h3>
              <p>{t('landing.f.chat.desc')}</p>
            </Link>
            <Link to="/media" className="feature-card">
              <div className="feature-icon">🎨</div>
              <h3>{t('landing.f.media.title')}</h3>
              <p>{t('landing.f.media.desc')}</p>
            </Link>
            <Link to="/3d" className="feature-card">
              <div className="feature-icon">📦</div>
              <h3>{t('landing.f.model3d.title')}</h3>
              <p>{t('landing.f.model3d.desc')}</p>
            </Link>
            <Link to="/games" className="feature-card">
              <div className="feature-icon">🎮</div>
              <h3>{t('landing.f.games.title')}</h3>
              <p>{t('landing.f.games.desc')}</p>
            </Link>
            <Link to="/history" className="feature-card">
              <div className="feature-icon">📋</div>
              <h3>{t('landing.f.history.title')}</h3>
              <p>{t('landing.f.history.desc')}</p>
            </Link>
          </div>
        </section>

      </section>

      {/* Features */}
      <section className="features">
        <h2 className="section-title">{t('landing.coreTitle')}</h2>
        <p className="product-lead">
          {t('landing.coreLead')}
        </p>
        <div className="feature-grid">
          <div className="feature-card">
            <div className="feature-icon">🧠</div>
            <h3>{t('landing.c.understand.title')}</h3>
            <p>{t('landing.c.understand.desc')}</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">🔄</div>
            <h3>{t('landing.c.decompose.title')}</h3>
            <p>{t('landing.c.decompose.desc')}</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">⚡</div>
            <h3>{t('landing.c.invoke.title')}</h3>
            <p>{t('landing.c.invoke.desc')}</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">🛡️</div>
            <h3>{t('landing.c.secure.title')}</h3>
            <p>{t('landing.c.secure.desc')}</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">🔌</div>
            <h3>{t('landing.c.scale.title')}</h3>
            <p>{t('landing.c.scale.desc')}</p>
          </div>
          <div className="feature-card">
            <div className="feature-icon">📡</div>
            <h3>{t('landing.c.transparent.title')}</h3>
            <p>{t('landing.c.transparent.desc')}</p>
          </div>
        </div>
      </section>

      {/* Architecture - Hidden */}
      <section className="arch" id="arch" style={{ display: 'none' }}>
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
        <p>{t('landing.disclaimer')}</p>
      </div>

      {/* Footer */}
      <footer className="landing-footer">
        <span>{t('landing.footer')}</span>
      </footer>
    </div>
  )
}
