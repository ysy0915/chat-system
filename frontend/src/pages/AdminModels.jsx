import React from 'react'

const STATIC_MODELS = [
  { icon: '🔮', name: 'DeepSeek', provider: 'deepseek', desc: '高性价比推理能力，擅长复杂逻辑推理与代码生成，响应速度快，成本优势显著' },
  { icon: '🤖', name: 'QWEN', provider: 'qwen', desc: '阿里通义千问，中文理解能力突出，支持长文本处理与多轮对话，综合能力均衡' },
  { icon: '🔥', name: 'DOUBAO', provider: 'doubao', desc: '字节跳动豆包大模型，创意写作与内容生成表现优异，多模态理解能力强' },
  { icon: '🚀', name: 'COPILOT', provider: 'copilot', desc: 'GitHub Copilot 驱动，代码补全与生成能力业界领先，深度集成开发场景优化' },
]

export default function AdminModels() {
  return (
    <div className="admin-root">
      <div className="admin-header">
        <div>
          <h2>模型管理</h2>
          <p className="admin-subtitle">管理 AI 模型配置，支持多模型切换</p>
        </div>
      </div>

      <div className="model-grid">
        {STATIC_MODELS.map((m, idx) => (
          <div className="model-card" key={m.provider} style={{ animationDelay: `${idx * 0.08}s` }}>
            <div className="model-card-header">
              <span className="model-provider-icon">{m.icon}</span>
            </div>
            <h3 className="model-name">{m.name}</h3>
            <p className="model-desc">{m.desc}</p>
            <div className="model-meta">
              <span className="model-tag">{m.provider}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
