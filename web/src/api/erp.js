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

export function kickSession(sessionId) {
  return request({ url: '/erp/session/kick', method: 'post', params: { sessionId } })
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

/* ================= 客户端版本（Eaglercraft 多版本热加装） ================= */
export function listClients() {
  return request({ url: '/erp/clients/list', method: 'get' })
}

/** Minecraft 版本枚举（版本名 + 协议号）。 */
export function listMcVersions() {
  return request({ url: '/erp/clients/mc-versions', method: 'get' })
}

export function clientImpact(id) {
  return request({ url: '/erp/clients/impact', method: 'get', params: { id } })
}

export function setClientEnabled(id, enabled, kick) {
  return request({ url: '/erp/clients/enabled', method: 'post', params: { id, enabled, kick } })
}

export function setDefaultClient(id) {
  return request({ url: '/erp/clients/default', method: 'post', params: { id } })
}

export function deleteClient(id) {
  return request({ url: '/erp/clients/delete', method: 'post', params: { id } })
}

export function reorderClients(ids) {
  return request({ url: '/erp/clients/order', method: 'post', data: { ids } })
}

export function setClientAllowSelect(allow) {
  return request({ url: '/erp/clients/allow-player-select', method: 'post', params: { allow } })
}

export function createClient(payload) {
  return request({ url: '/erp/clients/create', method: 'post', data: payload })
}

export function updateClient(id, payload) {
  return request({ url: '/erp/clients/update', method: 'post', params: { id }, data: payload })
}

export function initClientUpload(payload) {
  return request({ url: '/erp/clients/upload/init', method: 'post', data: payload })
}

export function uploadClientChunk(uploadId, index, data) {
  return request({ url: '/erp/clients/upload/chunk', method: 'post', params: { uploadId }, data: { index, data } })
}

export function finishClientUpload(uploadId) {
  return request({ url: '/erp/clients/upload/finish', method: 'post', params: { uploadId } })
}

export function abortClientUpload(uploadId) {
  return request({ url: '/erp/clients/upload/abort', method: 'post', params: { uploadId } })
}

// ===== 云游戏客户端 =====

export function listCloudClients(scope) {
  return request({ url: '/erp/cloud-clients/list', method: 'get', params: { scope } })
}

export function listCloudClientScopes() {
  return request({ url: '/erp/cloud-clients/scopes', method: 'get' })
}

export function setCloudClientScope(id, adminScope) {
  return request({ url: '/erp/cloud-clients/scope', method: 'post', params: { id, adminScope } })
}

export function setCloudClientEnabled(id, enabled) {
  return request({ url: '/erp/cloud-clients/enabled', method: 'post', params: { id, enabled } })
}

export function setDefaultCloudClient(id) {
  return request({ url: '/erp/cloud-clients/default', method: 'post', params: { id } })
}

export function deleteCloudClient(id, reason) {
  return request({ url: '/erp/cloud-clients/delete', method: 'post', params: { id, reason } })
}

export function forceEndCloudClientSession(id, reason) {
  return request({ url: '/erp/cloud-clients/session/force-end', method: 'post', params: { id, reason } })
}

export function restartCloudClientGame(id) {
  return request({ url: '/erp/cloud-clients/game/restart', method: 'post', params: { id } })
}

export function reorderCloudClients(ids) {
  return request({ url: '/erp/cloud-clients/order', method: 'post', data: { ids } })
}

export function setCloudClientAllowSelect(allow) {
  return request({ url: '/erp/cloud-clients/allow-player-select', method: 'post', params: { allow } })
}

export function createCloudClient(payload) {
  return request({ url: '/erp/cloud-clients/create', method: 'post', data: payload })
}

// ===== 云游戏客户端运行态（心跳/手动刷新/密钥） =====

export function refreshCloudClient(id) {
  return request({ url: '/erp/cloud-clients/refresh', method: 'post', params: { id } })
}

export function refreshAllCloudClients(scope) {
  return request({ url: '/erp/cloud-clients/refresh-all', method: 'post', params: { scope } })
}

export function updateCloudClientKey(id, key) {
  return request({ url: '/erp/cloud-clients/key', method: 'post', params: { id, key } })
}

export function updateCloudClient(id, payload) {
  return request({ url: '/erp/cloud-clients/update', method: 'post', params: { id }, data: payload })
}

// 下发客户端包更新：后台下载/解包，立刻返回 updating
export function pushCloudClientUpdate(id, packageId) {
  return request({ url: '/erp/cloud-clients/push-update', method: 'post', params: { id, packageId } })
}

// ===== 统一客户端制品库（客户端管理） =====

export function listClientPackages(type) {
  return request({ url: '/erp/client-packages/list', method: 'get', params: { type } })
}

export function updateClientPackage(id, payload) {
  return request({ url: '/erp/client-packages/update', method: 'post', params: { id }, data: payload })
}

export function setPreselectClientPackage(id) {
  return request({ url: '/erp/client-packages/default', method: 'post', params: { id } })
}

export function setClientPackageEnabled(id, enabled) {
  return request({ url: '/erp/client-packages/enabled', method: 'post', params: { id, enabled } })
}

export function deleteClientPackage(id) {
  return request({ url: '/erp/client-packages/delete', method: 'post', params: { id } })
}

export function initClientPackageUpload(payload) {
  return request({ url: '/erp/client-packages/upload/init', method: 'post', data: payload })
}

export function uploadClientPackageChunk(uploadId, index, data) {
  return request({
    url: '/erp/client-packages/upload/chunk',
    method: 'post',
    params: { uploadId },
    data: { index, data }
  })
}

export function finishClientPackageUpload(uploadId) {
  return request({ url: '/erp/client-packages/upload/finish', method: 'post', params: { uploadId } })
}

export function abortClientPackageUpload(uploadId) {
  return request({ url: '/erp/client-packages/upload/abort', method: 'post', params: { uploadId } })
}

export function registerExternalClientPackage(payload) {
  return request({ url: '/erp/client-packages/external', method: 'post', data: payload })
}

// ===== 云游戏客户端包制品库（旧端点兼容） =====

export function listCloudPackages() {
  return request({ url: '/erp/cloud-packages/list', method: 'get' })
}

export function updateCloudPackage(id, payload) {
  return request({ url: '/erp/cloud-packages/update', method: 'post', params: { id }, data: payload })
}

export function setDefaultCloudPackage(id) {
  return request({ url: '/erp/cloud-packages/default', method: 'post', params: { id } })
}

export function setCloudPackageEnabled(id, enabled) {
  return request({ url: '/erp/cloud-packages/enabled', method: 'post', params: { id, enabled } })
}

export function deleteCloudPackage(id) {
  return request({ url: '/erp/cloud-packages/delete', method: 'post', params: { id } })
}

export function initCloudPackageUpload(payload) {
  return request({ url: '/erp/cloud-packages/upload/init', method: 'post', data: payload })
}

export function uploadCloudPackageChunk(uploadId, index, data) {
  return request({
    url: '/erp/cloud-packages/upload/chunk',
    method: 'post',
    params: { uploadId },
    data: { index, data }
  })
}

export function finishCloudPackageUpload(uploadId) {
  return request({ url: '/erp/cloud-packages/upload/finish', method: 'post', params: { uploadId } })
}

export function abortCloudPackageUpload(uploadId) {
  return request({ url: '/erp/cloud-packages/upload/abort', method: 'post', params: { uploadId } })
}
