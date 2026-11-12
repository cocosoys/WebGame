import request from '@/utils/request'

/**
 * WebGame ERP 管理面 API。
 *
 * baseURL 由 SOYS 契约注入：生产 = /api/plugins/WebGame，开发 = /dev-api 代理；
 * 路径与后端 Controller 类级前缀 /erp/* 一一对应。
 */

/* ================= 运行概览（只读） ================= */
export function getStatus() {
  return request({ url: '/erp/status/overview', method: 'get' })
}

/* ================= WS 会话（只读） ================= */
export function listSessions() {
  return request({ url: '/erp/session/list', method: 'get' })
}

/* ================= 插件配置文件（数据驱动） ================= */
export function listConfigFiles() {
  return request({ url: '/erp/config/files', method: 'get' })
}

export function loadConfigFile(file) {
  return request({ url: '/erp/config/load', method: 'get', params: { file } })
}

export function saveConfigFile(file, data) {
  return request({ url: '/erp/config/save', method: 'post', params: { file }, data })
}
