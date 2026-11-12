/**
 * 令牌存取：复用 SOYS 主插件自带 auth（soys-auth.js 全局 SoysAuth）——
 * token 存 localStorage(soys_token) + Cookie(soys_session)，与主插件登录态跨页面共享。
 * 若 SoysAuth 未加载（开发模式），回退为内存态，避免 import 报错。
 */
const soysTokenKey = 'soys_token'

export function getToken() {
  if (typeof window !== 'undefined' && window.SoysAuth && window.SoysAuth.getToken) {
    return window.SoysAuth.getToken()
  }
  try { return localStorage.getItem(soysTokenKey) || '' } catch (e) { return '' }
}

export function setToken(token) {
  if (typeof window !== 'undefined' && window.SoysAuth && window.SoysAuth.setToken) {
    window.SoysAuth.setToken(token)
    return
  }
  try {
    if (token) { localStorage.setItem(soysTokenKey, token) } else { localStorage.removeItem(soysTokenKey) }
  } catch (e) {}
}

export function removeToken() {
  setToken('')
}
