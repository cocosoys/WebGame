/**
 * permission store：侧边栏 / 顶部栏 / 面包屑的菜单数据源。
 *
 * 方案 X：业务路由仍在 router/index.js 静态注册（保证 wujie 嵌入模式下不 404），
 * 但菜单的层级 / 标题 / 图标 / 顺序 / 可见性 / 权限全部以后端
 * MCERP GET /auth/getRouters 合成的 RouterVO 树为准，客户端按 moduleRoot 裁剪。
 * 前端只保留一张纯 path → component 绑定表（router/index.js 的 moduleRoutes），
 * 不再有任何展示元数据，杜绝阶梯四/七这类「手工对齐字段」回归。
 *
 * 四个槽必须全部由同一棵后端树填充：
 *  - Settings/index.vue:232 的 navType watcher 带 immediate:true，
 *    挂载即把 sidebarRouters 换成 defaultRoutes → 只改一个槽会被立即打回静态表；
 *  - TopNav/index.vue:160 切换顶部菜单时也会覆盖 sidebarRouters；
 *  - Breadcrumb/index.vue:44 与 HeaderSearch/index.vue:99 消费 defaultRoutes。
 * 每个槽各调一次 buildMenuTree 生成独立对象树，避免 TopNav 原地改 path 污染其他槽。
 */
import { constantRoutes } from '@/router'
import { getRouters } from '@/api/menu'
import { pickModuleTree, buildMenuTree } from '@/router/menuAdapter'

const permission = {
  state: {
    routes: [],
    addRoutes: [],
    defaultRoutes: [],
    topbarRouters: [],
    sidebarRouters: []
  },
  mutations: {
    SET_ROUTES: (state, routes) => {
      state.addRoutes = routes
      state.routes = constantRoutes.concat(routes)
    },
    SET_DEFAULT_ROUTES: (state, routes) => {
      state.defaultRoutes = constantRoutes.concat(routes)
    },
    SET_TOPBAR_ROUTES: (state, routes) => {
      state.topbarRouters = routes
    },
    SET_SIDEBAR_ROUTERS: (state, routes) => {
      state.sidebarRouters = routes
    },
  },
  actions: {
    // 生成路由
    GenerateRoutes({ commit }) {
      // 决策①：权限不足 / 请求失败时允许空侧边栏（只保留 constantRoutes 的首页项），
      // 但仍必须 resolve，否则 permission.js 守卫的 next() 永远不会被调用，页面卡在 NProgress。
      const empty = () => {
        commit('SET_ROUTES', [])
        commit('SET_SIDEBAR_ROUTERS', constantRoutes.slice())
        commit('SET_DEFAULT_ROUTES', [])
        commit('SET_TOPBAR_ROUTES', [])
      }

      return getRouters().then(res => {
        const moduleTree = pickModuleTree(res && res.data)
        if (!moduleTree) {
          // 后端 MenuRouteServiceImpl:45-47 模块级权限未通过时不下发整个模块 → 置空侧边栏
          empty()
          return []
        }
        // 四个槽各持一棵独立树；SET_SETTING/SET_DEFAULT_ROUTES 内部会 concat constantRoutes；
        // 这里 SET_SIDEBAR_ROUTERS 需手动 concat 以与 defaultRoutes 保持结构一致。
        commit('SET_ROUTES', buildMenuTree([moduleTree]))
        commit('SET_SIDEBAR_ROUTERS', constantRoutes.concat(buildMenuTree([moduleTree])))
        commit('SET_DEFAULT_ROUTES', buildMenuTree([moduleTree]))
        commit('SET_TOPBAR_ROUTES', buildMenuTree([moduleTree]))
        return []
      }).catch(err => {
        // 禁止静默兜底：显式告警，让用户能从控制台看到真实原因。
        console.error('[SOYS ERP] 后端菜单读取 / 适配失败，侧边栏置空：', err)
        empty()
        return []
      })
    }
  }
}

export default permission
