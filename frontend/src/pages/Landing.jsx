// frontend/src/pages/Landing.jsx
import React, { useEffect, useState, useRef } from 'react'
import { Link } from 'react-router-dom'

export default function Landing() {
  const [orbitAngle, setOrbitAngle] = useState(0)
  const animRef = useRef(null)
  const lastTimeRef = useRef(null)

  useEffect(() => {
    const animate = (time) => {
      if (lastTimeRef.current === null) lastTimeRef.current = time
      const delta = time - lastTimeRef.current
      lastTimeRef.current = time
      setOrbitAngle(prev => (prev + delta * 0.005) % 360)
      animRef.current = requestAnimationFrame(animate)
    }
    animRef.current = requestAnimationFrame(animate)
    return () => { cancelAnimationFrame(animRef.current) }
  }, [])

  const orbitNodes = [
    { label: 'Java', bg: '#333', border: '#333', color: '#fff' },
    { label: 'BigData', bg: '#333', border: '#333', color: '#fff' },
    { label: 'MCP', bg: '#333', border: '#333', color: '#fff' },
    { label: 'Agent', bg: '#333', border: '#333', color: '#fff' },
  ]

  const rotRad = (orbitAngle / 180) * Math.PI

  return (
    <div className="landing">
      <section className="hero">
        <h1 className="hero-title">博思AI聊天论坛</h1>
        <p className="hero-desc">
          应用架构、中间件架构、基础设施与AI架构的融合架构，构建具备感知、规划、执行与反思能力的企业级智能体
        </p>
        <div className="hero-actions">
          <Link to="/" className="btn-glow">
            <span>开始对话</span>
            <span className="btn-arrow">→</span>
          </Link>
          <a href="#arch" className="btn-outline">了解架构</a>
          <Link to="/graph" className="btn-outline">问答图谱</Link>
          <Link to="/history" className="btn-outline">问答列表</Link>
          <Link to="/admin/models" className="btn-outline">模型管理</Link>
        </div>

        <div className="hero-visual">
          <svg className="orbit-svg" width="520" height="520" viewBox="0 0 520 520">
            <defs>
              <radialGradient id="coreGlow" cx="50%" cy="50%" r="50%">
                <stop offset="0%" stopColor="rgba(56,189,248,0.25)" />
                <stop offset="100%" stopColor="rgba(56,189,248,0)" />
              </radialGradient>
            </defs>
            <circle cx="260" cy="260" r="190"
                    fill="none" stroke="rgba(56,189,248,0.08)"
                    strokeWidth="1" strokeDasharray="8 6" />
            {orbitNodes.map((_, i) => {
              const angle = (2 * Math.PI * i) / orbitNodes.length - Math.PI / 2 + rotRad
              const nx = 260 + 190 * Math.cos(angle)
              const ny = 260 + 190 * Math.sin(angle)
              return (
                <line key={'line-' + i}
                      x1="260" y1="260" x2={nx} y2={ny}
                      stroke="rgba(56,189,248,0.12)"
                      strokeWidth="1.5" strokeDasharray="6 4" />
              )
            })}
            {orbitNodes.map((node, i) => {
              const angle = (2 * Math.PI * i) / orbitNodes.length - Math.PI / 2 + rotRad
              const nx = 260 + 190 * Math.cos(angle)
              const ny = 260 + 190 * Math.sin(angle)
              return (
                <g key={'node-' + i}>
                  <circle cx={nx} cy={ny} r="40"
                          fill={node.bg} stroke={node.border} strokeWidth="2" />
                  <text x={nx} y={ny + 6} textAnchor="middle"
                        fontSize="15" fontWeight="700" fill={node.color}>
                    {node.label}
                  </text>
                </g>
              )
            })}
          </svg>
          <div className="orbit-core">
            <span>AI</span>
          </div>
        </div>
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

      {/* Footer */}
      <footer className="landing-footer">
        <span>Powered by Application × Middleware × Infrastructure × AI Architecture</span>
      </footer>
    </div>
  )
}
