import request from '@/utils/request'
import { MCERP_HOST_API } from '@/api/login'

/**
 * 读取 MCERP 主应用按当前凭证合成的路由树。
 *
 * 返回 AjaxResult.success(List<RouterVO>) → utils/request.js 的响应拦截器
 * 返回整个 AjaxResult（:84/:109），故调用方取 res.data 即 RouterVO[]。
 *
 * ⚠️ 必须显式覆盖 baseURL：utils/request.js:19 的 axios 实例默认 baseURL 是
 * 本插件的 apiBaseURL（/api/plugins/SOYSHTTPOverMC-ERP），而 getRouters 登记在
 * MCERP 命名空间（ErpAuthController.java:23/46-48）。不覆盖会拼成
 * /api/plugins/SOYSHTTPOverMC-ERP/api/plugins/MCERP/auth/getRouters → 404。
 *
 * 该请求只在独立访问时发出：permission.js:23-27 在 wujie 模式下直接放行，
 * 不会 dispatch GetInfo / GenerateRoutes。
 */
export function getRouters() {
  return request({
    url: '/auth/getRouters',
    method: 'get',
    baseURL: MCERP_HOST_API
  })
}