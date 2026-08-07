import React from 'react'

const MODEL_TYPE_LABELS = {
  chat:        { label: '对话',   color: '#4f8ef7' },
  image:       { label: '图形生成', color: '#9b59b6' },
  video:       { label: '视频生成', color: '#e67e22' },
  '3d':        { label: '3D模型生成', color: '#10b981' },
  text_parse:  { label: '文本解析', color: '#27ae60' },
  image_parse: { label: '图片解析', color: '#16a085' },
}

const STATIC_MODELS = [
  { icon: '🔮', name: 'DeepSeek',    provider: 'deepseek', modelType: 'chat',        desc: '高性价比推理能力，擅长复杂逻辑推理与代码生成，响应速度快，成本优势显著' },
  { icon: '🤖', name: 'QWEN',        provider: 'qwen',     modelType: 'chat',        desc: '阿里通义千问，中文理解能力突出，支持长文本处理与多轮对话，综合能力均衡' },
  { icon: '🔥', name: 'DOUBAO',      provider: 'doubao',   modelType: 'chat',        desc: '字节跳动豆包大模型，创意写作与内容生成表现优异，多模态理解能力强' },
  { icon: '🎨', name: 'QWEN Image',  provider: 'qwen',     modelType: 'image',       desc: '通义千问图像生成模型，支持高质量文生图，风格多样，细节表现力出色' },
  { icon: '🎬', name: 'WAN Video',   provider: 'dashscope',modelType: 'video',       desc: '阿里万象视频生成模型，支持文生视频，画面流畅，场景丰富' },
  { icon: '📦', name: 'HY-3D',       provider: 'tencent',  modelType: '3d',          desc: '腾讯混元3D模型，支持文生3D模型，生成GLB/OBJ格式，可用于游戏、VR、3D打印' },
  { icon: '🧬', name: 'GLM (智谱)',  provider: 'zhipu',    modelType: 'text_parse',  desc: '智谱 AI GLM 系列大模型，中文语义理解深厚，擅长文档解析与长文本处理' },
  { icon: '👁️', name: 'QWEN Vision', provider: 'qwen',     modelType: 'image_parse', desc: '通义千问视觉理解模型，支持图片内容识别、OCR、场景分析等多种图像理解任务' },
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
        {STATIC_MODELS.map((m, idx) => {
          const typeInfo = MODEL_TYPE_LABELS[m.modelType] || { label: m.modelType, color: '#888' }
          return (
            <div className="model-card" key={m.provider + m.modelType + idx} style={{ animationDelay: `${idx * 0.08}s` }}>
              <div className="model-card-header">
                <span className="model-provider-icon">{m.icon}</span>
                <span
                  className="model-type-badge"
                  style={{ background: typeInfo.color }}
                >
                  {typeInfo.label}
                </span>
              </div>
              <h3 className="model-name">{m.name}</h3>
              <p className="model-desc">{m.desc}</p>
              <div className="model-meta">
                <span className="model-tag">{m.provider}</span>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
