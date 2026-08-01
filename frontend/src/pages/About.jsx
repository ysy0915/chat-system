import React from 'react'
import { Link } from 'react-router-dom'

export default function About() {
  return (
    <div className="about-page">
      <Link to="/home" className="btn-back-home">← 返回首页</Link>
      <div className="about-card">
        <div className="about-header">
          <div className="about-avatar">
            <div className="about-avatar-ring" />
            <img src="/chat/assets/杨思义.jpg" alt="杨思义" className="about-avatar-img" />
          </div>
          <h1 className="about-name">杨思义</h1>
          <p className="about-title">数据工程架构师 · AI 基础架构方向</p>
        </div>

        <div className="about-divider" />

        <div className="about-section">
          <h2 className="about-section-title">个人简介</h2>
          <div className="about-timeline">
            <div className="about-timeline-item">
              <div className="about-timeline-dot" />
              <div className="about-timeline-content">
                <div className="about-timeline-label">教育与早期经历</div>
                <p className="about-timeline-text">
                  2015 于<span className="about-highlight">山东大学</span>，获得硕士学位。
                </p>
              </div>
            </div>
            <div className="about-timeline-item">
              <div className="about-timeline-dot" />
              <div className="about-timeline-content">
                <div className="about-timeline-label">职业积累</div>
                <p className="about-timeline-text">
                  在<span className="about-highlight">蚂蚁集团</span>工作期间担任<span className="about-highlight">技术专家</span>，积累了约 <span className="about-highlight">11 年</span>的工作经验，打下了扎实的软件工程基本盘。
                </p>
              </div>
            </div>
            <div className="about-timeline-item">
              <div className="about-timeline-dot" />
              <div className="about-timeline-content">
                <div className="about-timeline-label">工作风格</div>
                <p className="about-timeline-text">
                  以对技术与工作<span className="about-highlight">极度专注、踏实肯干</span>的态度著称，并持续进行行业技术分享，致力于影响和感染更多的人。
                </p>
              </div>
              <div className="about-timeline-content">
                <div className="about-timeline-label">工作风格</div>
                <p className="about-timeline-text">
                  杨思义的技术博客：<a href="https://yangsiyi.cn" target="_blank" rel="noopener noreferrer">https://www.cnblogs.com/yangsy0915</a>
                </p>
              </div>
            </div>
            <div className="about-timeline-item">
              <div className="about-timeline-dot about-timeline-dot-active" />
              <div className="about-timeline-content">
                <div className="about-timeline-label">未来规划</div>
                <p className="about-timeline-text">
                  目前担任<span className="about-highlight">数据工程架构师</span>职位，计划全心投入 <span className="about-highlight">AI 基础架构及全链路调优</span>（包含算法），致力于解决企业级甚至世界级的 <span className="about-highlight">0→1</span> 建设问题。
                </p>
              </div>
            </div>
          </div>
        </div>

        <div className="about-divider" />

        <div className="about-section">
          <h2 className="about-section-title">代表项目 · 博思</h2>
          <p className="about-text">
            「博思」是一个集成了多模型 AI 对话、知识图谱、图片与视频生成等功能的智能问答平台。
            支持 DeepSeek、通义千问、豆包等多个大模型并行推理，通过 RabbitMQ 消息队列实现异步处理，Redis 缓存加速问答响应，大数据流批一体数据处理。
          </p>
          <div className="about-tech-grid">
            <div className="about-tech-item">
              <span className="about-tech-icon">☕</span>
              <div>
                <div className="about-tech-name">后端</div>
                <div className="about-tech-desc">Spring Boot · MyBatis · Spring Security · WebSocket</div>
              </div>
            </div>
            <div className="about-tech-item">
              <span className="about-tech-icon">⚛</span>
              <div>
                <div className="about-tech-name">前端</div>
                <div className="about-tech-desc">React · Vite · React Router · Axios · SockJS</div>
              </div>
            </div>
            <div className="about-tech-item">
              <span className="about-tech-icon">🗄</span>
              <div>
                <div className="about-tech-name">数据与中间件</div>
                <div className="about-tech-desc">MySQL · Redis · RabbitMQ  · Spark · Flink</div>
              </div>
            </div>
            <div className="about-tech-item">
              <span className="about-tech-icon">🤖</span>
              <div>
                <div className="about-tech-name">AI 模型</div>
                <div className="about-tech-desc">DeepSeek · QWEN · DOUBAO · 通义万相</div>
              </div>
            </div>
          </div>
        </div>

        <div className="about-footer">
          <p className="about-footer-text">制作者：杨思义</p>
        </div>
      </div>
    </div>
  )
}
