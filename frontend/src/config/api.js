/**
 * API 配置中心
 * 所有 API 和 WebSocket 地址统一在这里管理
 */
const API_BASE = import.meta.env.VITE_API_BASE || ''
const WS_BASE = import.meta.env.VITE_WS_BASE || ''

export const API = {
  // 对话
  MESSAGES: `${API_BASE}/api/v1/messages`,
  MESSAGES_RECENT: `${API_BASE}/api/v1/messages/recent`,
  MESSAGES_SEARCH: `${API_BASE}/api/v1/messages/search`,
  MESSAGES_CONTEXT: `${API_BASE}/api/v1/messages/context`,
  MESSAGES_ONLINE: (page) => `${API_BASE}/api/v1/messages/online-count?page=${page}`,
  MESSAGES_STOP: `${API_BASE}/api/v1/messages/stop`,
  MESSAGES_REGENERATE: `${API_BASE}/api/v1/messages/regenerate`,
  MESSAGES_WITH_FILE: `${API_BASE}/api/v1/messages/with-file`,

  // 辩论
  DEBATE: `${API_BASE}/api/v1/debate`,

  // 树洞
  TREEHOLE_ASK: `${API_BASE}/api/v1/treehole/ask`,
  TREEHOLE_ASK_WITH_FILE: `${API_BASE}/api/v1/treehole/ask-with-file`,
  TREEHOLE_RECENT: `${API_BASE}/api/v1/treehole/recent`,
  TREEHOLE_SEARCH: `${API_BASE}/api/v1/treehole/search`,
  TREEHOLE_CONTEXT: `${API_BASE}/api/v1/treehole/context`,
  TREEHOLE_STOP: `${API_BASE}/api/v1/treehole/stop`,
  TREEHOLE_REGENERATE: `${API_BASE}/api/v1/treehole/regenerate`,

  // 知识图谱
  GRAPH: `${API_BASE}/api/v1/graph`,
  GRAPH_STATS: `${API_BASE}/api/v1/graph/stats`,
  GRAPH_SEARCH: (keyword, limit) => `${API_BASE}/api/v1/graph/search?keyword=${encodeURIComponent(keyword)}&limit=${limit}`,

  // 知识库RAG
  RAG_KB: `${API_BASE}/api/v1/rag/kb`,
  RAG_KB_DOCUMENTS: (kbId) => `${API_BASE}/api/v1/rag/kb/${kbId}/documents`,
  RAG_DOCUMENTS: `${API_BASE}/api/v1/rag/documents`,
  RAG_SEARCH: `${API_BASE}/api/v1/rag/search`,

  // 多模态
  MEDIA_GENERATE: `${API_BASE}/api/v1/media/generate`,
  MEDIA_HISTORY: `${API_BASE}/api/v1/media/history`,
  MEDIA_STATUS: (recordId) => `${API_BASE}/api/v1/media/status/${recordId}`,
  MEDIA_3D_ACCESS: `${API_BASE}/api/v1/media/3d-access`,

  // LLM 模型管理（经 chat-web 转发到 chat-llm，写操作需管理员密码）
  LLM_ADMIN_LOGIN: `${API_BASE}/api/v1/llm/admin/login`,
  LLM_PROVIDERS: `${API_BASE}/api/v1/llm/admin/providers`,
  LLM_PROVIDER_TYPES: `${API_BASE}/api/v1/llm/admin/providers/types`,
  LLM_PROVIDER: (id) => `${API_BASE}/api/v1/llm/admin/providers/${id}`,
  LLM_PROVIDER_RELOAD: `${API_BASE}/api/v1/llm/admin/providers/reload`,

  // 用户
  LOGIN: `${API_BASE}/api/v1/auth/login`,
  REGISTER: `${API_BASE}/api/v1/auth/register`,
  PROFILE: `${API_BASE}/api/v1/profile`,

  // SQL执行器
  SQL_LOGIN: `${API_BASE}/api/v1/sql/login`,
  SQL_EXECUTE: `${API_BASE}/api/v1/sql/execute`,

  // 服务健康（games 高峰降级时前端维护提示）
  GAMES_HEALTH: `${API_BASE}/api/v1/health/games`,

  // 监控
  MONITOR_ONLINE: (page) => `${API_BASE}/api/v1/messages/online-count?page=${page}`,

  // WebSocket
  WS_CHAT: (userId) => `${WS_BASE}/ws/chat?userId=${userId}`,
}

export default API
