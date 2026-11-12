/**
 * 后端 RouterVO → 前端菜单树适配层。
 *
 * 数据源：MCERP GET /auth/getRouters 返回的完整 RouterVO[] 中，
 * path === soysContext.moduleRoot 的那一棵模块子树（MenuRouteServiceImpl 合成）。
 * 后端已完备：模块级 / 菜单级 perms 与 visible 过滤、M 类型递归 setChildren、
 * setIcon 缺失兜底（form / link）、alwaysShow、hidden、meta.title/icon/noCache/link、
 * 以及声明顺序（toModuleVO 直接 addAll，不排序）。
 *
 * 前端只做三件事：
 *  1) 按「完整递归 path」查 router/index.js 的纯 path → component 表，
 *     把 component 字段（Layout / wujie:URL / iframe:URL）替换为真实 Vue 组件。
 *     ⚠️ 绝不直接按 URL 去映射 WujieLink / InnerLink —— 子应用会把把自己当 wujie
 *     子应用再嵌套一层，形成无限嵌套。
 *  2) 丢弃权限过滤后 children 为空的目录，避免出现点不动的空壳目录。
 *  3) 不修改 path / children 层级 / meta / alwaysShow / hidden / name。
 *     path 必须保持后端的相对段，SidebarItem.resolvePath 才能逐级拼出正确 URL。
 *
 * 每次调用都返回全新对象树：四个 store 槽（sidebar / default / topbar / routes）
 * 各持一个独立引用。TopNav.childrenMenus 会原地改写 router.children[].path，
 * 若与其他槽共享引用会污染侧边栏（MCERP 用两次深拷贝解决同一问题）。
 */
import soysContext from '@/soys/context'
import { moduleRoutes } from './index'

/** 拼接完整递归 path（外链与绝对路径原样保留，剥离 query）。 */
function joinPath(parent, segment) {
  const raw = segment == null ? '' : String(segment)
  if (/^(https?:|mailto:|\/\/)/i.test(raw)) return raw
  const seg = raw.split('?')[0]
  if (seg.charAt(0) === '/') return seg
  if (!parent) return '/' + seg
  return parent.replace(/\/$/, '') + '/' + seg
}

/**
 * 从全量 RouterVO[] 裁出本模块子树。
 * 模块级权限 soyshttpovermc:erp:menu 未通过时后端不会下发该模块 → 返回 null，
 * 由 store 按决策置空侧边栏。
 */
export function pickModuleTree(routers) {
  if (!Array.isArray(routers)) return null
  return routers.find(r => r && r.path === soysContext.moduleRoot) || null
}

/**
 * 递归适配为可渲染的菜单树。纯函数，不改写入参。
 * 缺组件绑定即抛错（要兜底）：说明后端新增了菜单而前端未登记对应视图，
 * 应立即暴露，而不是渲染一个点击进去白屏的菜单项。
 */
export function buildMenuTree(nodes, parentPath = '') {
  if (!Array.isArray(nodes)) return []
  const out = []
  nodes.forEach(node => {
    if (!node || node.hidden) return
    const fullPath = joinPath(parentPath, node.path)
    const children = buildMenuTree(node.children, fullPath)
    // 后端 M 目录与有子项的模块根 component 均为 'Layout'；
    // C 叶子是 wujie:URL / iframe:URL。过滤后无子项的目录不再渲染。
    if (node.component === 'Layout' && children.length === 0) return
    const component = moduleRoutes[fullPath]
    if (!component) {
      throw new Error('[SOYS] 后端菜单缺少本地组件绑定：' + fullPath)
    }
    const item = {
      path: node.path,
      name: node.name,
      hidden: false,
      alwaysShow: !!node.alwaysShow,
      meta: Object.assign({}, node.meta || {}),
      component
    }
    if (children.length) item.children = children
    out.push(item)
  })
  return out
}
