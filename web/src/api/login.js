import request from '@/utils/request'
import soysContext from '@/soys/context'

/**
 * 独立访问模式的登录 / 鉴权（决策：保留）。
 *
 * - 登录 / 登出走 SOYS 主插件自带 auth：契约 apiPrefix + /auth/*，无兜底。
 * - getInfo 走 MCERP 宿主命名空间，取沿用契约的 user / roles / permissions。
 *
 * 已知限制：契约 pluginsPrefix 指向 owner = 本插件，并未暴露「宿主插件标识」字段，
 * 因此 HOST_PLUGIN_ID 是前端唯一残留的跨插件硬编码常量；其中 /api 段仍从契约 apiPrefix
 * 取得（改 api-prefix 不会失效）。若后续契约补充该字段，这里应改为直接读取。
 */
const HOST_PLUGIN_ID = 'MCERP'
// 导出供 api/menu.js 复用：getRouters 与 getInfo 同属 MCERP 命名空间，
// 不新增第二份跨插件硬编码（契约未提供宿主插件标识字段）。
export const MCERP_HOST_API = soysContext.apiPrefix + '/plugins/' + HOST_PLUGIN_ID

// 登录方法：直接调用主插件自带弹窗登录 POST <apiPrefix>/auth/login
// 成功返回 { token, player }（SOYS 契约：data.token / data.player）
export function login(username, password, code, uuid) {
  return window.SoysAuth.request(soysContext.apiPrefix + '/auth/login', {
    method: 'POST',
    json: true,
    body: JSON.stringify({ username, password, remember: true })
  }).then(r => {
    if (r.code === 200 && r.data && r.data.token) {
      return { token: r.data.token, player: r.data.player }
    }
    throw new Error(r.msg || '登录失败')
  })
}

// 注册方法（SOYS 无自建注册端点；保留签名避免 import 断裂）
export function register(data) {
  return Promise.reject(new Error('注册功能由主插件/游戏内处理'))
}

// 获取用户详细信息（若依契约，MCERP 主插件端点 /auth/getInfo → /api/plugins/MCERP/auth/getInfo）
export function getInfo() {
  return request({
    url: '/auth/getInfo',
    method: 'get',
    baseURL: MCERP_HOST_API
  })
}

// 解锁屏幕
export function unlockScreen(password) {
  return request({
    url: '/unlockscreen',
    method: 'post',
    data: { password }
  })
}

// 退出方法：直接调用主插件自带 POST /api/auth/logout
export function logout() {
  return window.SoysAuth.request(soysContext.apiPrefix + '/auth/logout', { method: 'POST' })
    .then(r => {
      if (r.code !== 200) throw new Error(r.msg || '退出失败')
    })
}

// 获取验证码（MCERP 已废弃自建验证码；保留导出避免 register.vue import 断裂）
export function getCodeImg() {
  return Promise.resolve({ captchaEnabled: false, img: '', uuid: '' })
}
