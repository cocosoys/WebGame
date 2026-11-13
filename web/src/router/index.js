import Vue from 'vue'
import Router from 'vue-router'
import soysContext from '@/soys/context'

Vue.use(Router)

/* Layout：wujie 嵌入时主应用 MCERP 提供侧边栏（空 Layout）；直接访问时用完整 Layout（自带侧边栏） */
import RealLayout from '@/layout'
import EmptyLayout from '@/layout/Empty'
import NestedRouterView from '@/layout/NestedRouterView'

// wujie 子应用标记（主应用加载时注入 window.__POWERED_BY_WUJIE__）
export const isWujie = typeof window !== 'undefined' && !!window.__POWERED_BY_WUJIE__
const Layout = isWujie ? EmptyLayout : RealLayout

// 模块路由根：由契约 erpModuleRoot 提供（MCERP 按 owner 插件名 NameSafe.path 派生，
// WebGame-ERP → /webgameerp），与 MCERP MenuRouteServiceImpl 合成的模块路由 path 完全一致。
// 视图文件的物理目录名（src/views/webgameerp/**）属构建期资源路径，与此 URL 根互不影响。
const moduleRoot = soysContext.moduleRoot

/**
 * 公共路由：所有模式都加载（基础页面 + 首页）。
 * 业务菜单的「展示数据」由后端 MCERP /auth/getRouters 下发，见 store/modules/permission.js。
 */
export const constantRoutes = [
  {
    path: '/redirect',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/redirect')
      }
    ]
  },
  {
    path: '/login',
    component: () => import('@/views/login'),
    hidden: true
  },
  {
    path: '/register',
    component: () => import('@/views/register'),
    hidden: true
  },
  {
    path: '/404',
    component: () => import('@/views/error/404'),
    hidden: true
  },
  {
    path: '/401',
    component: () => import('@/views/error/401'),
    hidden: true
  },
  {
    path: '',
    component: Layout,
    redirect: 'index',
    children: [
      {
        path: 'index',
        component: () => import('@/views/index'),
        name: 'Index',
        meta: { title: '首页', icon: 'dashboard', affix: true }
      }
    ]
  },
  {
    path: '/lock',
    component: () => import('@/views/lock'),
    hidden: true,
    meta: { title: '锁定屏幕' }
  }
]

/**
 * 前端唯一需要维护的绑定：完整递归 path → Vue 组件。
 *
 * 决策①：moduleRoutes 降级为「纯 path → component 表」——不含 title / icon /
 * alwaysShow / children 等任何展示元数据。菜单的层级、标题、图标、顺序、可见性、
 * 权限全部以后端 MCERP MenuRouteServiceImpl 合成的 RouterVO 树为准
 * （GET /auth/getRouters → 客户端按 path === moduleRoot 裁剪）。
 *
 * 键值必须是「完整递归 path」，而非相对段：dir("config") 与其下同名叶子
 * config（核心配置）都叫 config，用相对段建表会撞键（与侧边栏 submenuIndex
 * 冲突同源于此）。目录节点统一用 NestedRouterView（纯 <router-view/>）——
 * 复用 RealLayout 会叠出多层侧边栏，复用 Empty.vue 会叠出多层 100vh 外壳。
 *
 * name 只用于 vue-router 的路由身份（TagsView / keep-alive 缓存键），
 * 与菜单展示无关；后端生成的 name 不参与渲染，见 router/menuAdapter.js。
 */
const routeBindings = {
  [moduleRoot]: { component: Layout, name: 'WebGameErpRoot' },
  [moduleRoot + '/overview']: { component: () => import('@/views/webgameerp/overview/index'), name: 'WebGameOverview' },
  [moduleRoot + '/session']: { component: () => import('@/views/webgameerp/session/index'), name: 'WebGameSession' },
  [moduleRoot + '/config']: { component: NestedRouterView, name: 'WebGameConfigDir' },
  [moduleRoot + '/config/config']: { component: () => import('@/views/webgameerp/config/config'), name: 'WebGameConfig' },
  [moduleRoot + '/clients']: { component: () => import('@/views/webgameerp/clients/index'), name: 'WebGameClients' }
}

/** 决策②：对外暴露的纯 path → component 表（供 router/menuAdapter.js 查表）。 */
export const moduleRoutes = Object.keys(routeBindings).reduce((table, fullPath) => {
  table[fullPath] = routeBindings[fullPath].component
  return table
}, {})

/**
 * 由绑定表生成 vue-router 静态路由树（方案 X：保留静态注册）。
 * 层级由 path 前缀自动推导，前端不再手写 children 嵌套——避免与后端
 * 层级或归档差异的回归被彻底消除。
 * 根里不含 meta.title / meta.icon / alwaysShow：这些是展示元数据，全部来自后端。
 */
function buildStaticRoutes(bindings) {
  const allPaths = Object.keys(bindings)
  if (!bindings[moduleRoot]) {
    throw new Error('[SOYS] 组件绑定表缺失模块根：' + moduleRoot)
  }
  const build = (fullPath) => {
    const binding = bindings[fullPath]
    const node = {
      path: fullPath === moduleRoot ? moduleRoot : fullPath.substring(fullPath.lastIndexOf('/') + 1),
      component: binding.component,
      name: binding.name
    }
    // 根路径重定向到第一个业务页（vue-router 行为，不是菜单展示数据）
    if (fullPath === moduleRoot) {
      node.redirect = moduleRoot + '/overview'
    }
    // 直接子：以 fullPath + '/' 开头、且剩余部分不再含 '/'
    const childPaths = allPaths.filter(p =>
      p.indexOf(fullPath + '/') === 0 && p.substring(fullPath.length + 1).indexOf('/') === -1
    )
    if (childPaths.length) {
      node.children = childPaths.map(build)
    }
    return node
  }
  return [build(moduleRoot)]
}

/** 仅用于 vue-router 注册；不作菜单数据源（菜单走后端 RouterVO 树）。 */
export const staticRoutes = buildStaticRoutes(routeBindings)

// 动态路由（预留）：菜单由后端 getRouters 驱动（ERP 模块菜单），此处保持空
export const dynamicRoutes = []

// 防止连续点击多次路由报错
let routerPush = Router.prototype.push
let routerReplace = Router.prototype.replace
// push
Router.prototype.push = function push(location) {
  return routerPush.call(this, location).catch(err => err)
}
// replace
Router.prototype.replace = function push(location) {
  return routerReplace.call(this, location).catch(err => err)
}

export default new Router({
  // 契约声明插件级 SPA 回退才可用 history；否则 hash，避免深层 URL 刷新 404
  mode: soysContext.capabilities.spaFallback ? 'history' : 'hash',
  base: soysContext.pageBase,
  scrollBehavior: () => ({ y: 0 }),
  routes: constantRoutes.concat(staticRoutes)
})
