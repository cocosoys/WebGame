/**
 * SOYSHTTPOverMC-ERP 子应用入口（RuoYi-Vue2）。
 * <p>既支持独立部署，也支持被 MCERP 主应用以 wujie 微前端方式嵌入。
 * <p>wujie 模式下监听 __WUJIE_MOUNT/__WUJIE_UNMOUNT 生命周期，
 * 通过全局 bus 接收主应用 router-push 事件做内部导航。
 */
import Vue from 'vue'

// SOYS 平台契约（前端书写；apiBase 占位符由托管层注入真值，默认 /api）
import '@/soys/context'

import Cookies from 'js-cookie'

import Element from 'element-ui'
import './assets/styles/element-variables.scss'

import '@/assets/styles/index.scss' // global css
import '@/assets/styles/ruoyi.scss' // ruoyi css
import App from './App'
import store from './store'
import router from './router'
import directive from './directive' // directive
import plugins from './plugins' // plugins
import { download } from '@/utils/request'

import './assets/icons' // icon
import './permission' // permission control
import { parseTime, resetForm, addDateRange, selectDictLabel, selectDictLabels, handleTree } from "@/utils/ruoyi"
// 分页组件
import Pagination from "@/components/Pagination"
// 自定义表格工具组件
import RightToolbar from "@/components/RightToolbar"
// 富文本组件
import Editor from "@/components/Editor"
// 文件上传组件
import FileUpload from "@/components/FileUpload"
// 图片上传组件
import ImageUpload from "@/components/ImageUpload"
// 图片预览组件
import ImagePreview from "@/components/ImagePreview"

// 全局方法挂载
Vue.prototype.parseTime = parseTime
Vue.prototype.resetForm = resetForm
Vue.prototype.addDateRange = addDateRange
Vue.prototype.selectDictLabel = selectDictLabel
Vue.prototype.selectDictLabels = selectDictLabels
Vue.prototype.download = download
Vue.prototype.handleTree = handleTree

// 全局组件挂载
Vue.component('Pagination', Pagination)
Vue.component('RightToolbar', RightToolbar)
Vue.component('Editor', Editor)
Vue.component('FileUpload', FileUpload)
Vue.component('ImageUpload', ImageUpload)
Vue.component('ImagePreview', ImagePreview)

Vue.use(directive)
Vue.use(plugins)

/**
 * If you don't want to use mock-server
 * you want to use MockJs for mock api
 * you can execute: mockXHR()
 *
 * Currently MockJs will be used in the production environment,
 * please remove it before going online! ! !
 */

// wujie 以 shadow DOM 承载子应用：Element 的 popper 默认 append 到 shadow body，
// 其偏移按 shadow 文档坐标计算，但 position:fixed 最终按宿主视口渲染，导致下拉层整体偏移。
// Element 只把 $ELEMENT 用于 size，不识别 popperAppendToBody；同时 popper 只要检测到
// 祖先有 fixed（如 el-dialog__wrapper）就会用 fixed 定位。这里统一改为：
// 1) popper 不 append 到 body；2) forceAbsolute 强制绝对定位，让计算与渲染在同一坐标系。
const isWujie = typeof window !== 'undefined' && !!window.__POWERED_BY_WUJIE__

Vue.use(Element, {
  size: Cookies.get('size') || 'medium' // set element-ui default size
})

if (isWujie) {
  const popperAppendToBodyProps = {
    ElSelect: 'popperAppendToBody',
    ElAutocomplete: 'popperAppendToBody',
    ElDatePicker: 'appendToBody',
    ElTimePicker: 'appendToBody',
    ElCascader: 'appendToBody'
  }
  const forceAbsolutePopperOptions = () => ({ gpuAcceleration: false, forceAbsolute: true })
  // 递归处理组件及其局部子组件（el-select-menu 这类内部组件各自持有 props 副本）
  const patchPopperOptions = (options, depth) => {
    if (!options || typeof options !== 'object' || depth > 3) return
    const props = options.props
    if (props && props.popperOptions && typeof props.popperOptions.default === 'function') {
      props.popperOptions.default = forceAbsolutePopperOptions
    }
    if (options.components) {
      Object.keys(options.components).forEach(key => {
        const child = options.components[key]
        patchPopperOptions(child && child.options ? child.options : child, depth + 1)
      })
    }
  }
  Object.keys(Vue.options.components).forEach(name => {
    const ctor = Vue.options.components[name]
    const options = ctor && ctor.options
    if (!options) return
    const key = popperAppendToBodyProps[name]
    if (key && options.props && options.props[key] && 'default' in options.props[key]) {
      options.props[key].default = false
    }
    patchPopperOptions(options, 0)
  })
}

Vue.config.productionTip = false

/**
 * 复用 SOYS 主插件 auth：登录成功统一跳转——
 * 登录页（#/login）→ 进入首页；其他页面（401 弹窗补登）→ 刷新当前页重发请求。
 */
if (typeof window !== 'undefined' && window.SoysAuth) {
  window.SoysAuth.onLoginSuccess = function (player, mode) {
    if (router.currentRoute.path === '/login') {
      router.push('/')
    } else {
      window.location.reload()
    }
  }
}

// wujie 微前端：主应用卸载子应用时销毁实例，卸载后再重新挂载
let vm = null
let busOff = null
function mount() {
  vm = new Vue({
    router,
    store,
    render: h => h(App)
  }).$mount('#app')
}

if (isWujie) {
  // wujie 生命周期钩子：mount 时接收主应用 props（含初始 path），并监听路由切换
  window.__WUJIE_MOUNT = (props) => {
    mount()
    // wujie 模式下由主应用 MCERP 提供侧边栏，子应用只用 EmptyLayout 渲染页面内容，
    // 不挂载 Sidebar / TopNav / Settings / Breadcrumb（layout/index.vue 才有这些组件）。
    // 业务路由已在 router/index.js 静态注册，此处不再提交任何静态菜单表——
    // moduleRoutes 现为纯 path → component 映射表，填进菜单树只会产生空数据。
    // 初始路由：主应用传 path 则跳过去（如 /webgameerp/overview）
    if (props && props.path && router.currentRoute.path !== props.path) {
      router.push(props.path)
    }
    // 主应用菜单切换时通过 bus 发 router-push(path)，子应用内部导航（避免整页重载）
    const bus = window.$wujie && window.$wujie.bus
    if (bus) {
      const handler = (path) => {
        if (path && router.currentRoute.path !== path) router.push(path)
      }
      bus.$on('router-push', handler)
      busOff = () => bus.$off('router-push', handler)
    }
  }
  window.__WUJIE_UNMOUNT = () => {
    if (busOff) { busOff(); busOff = null }
    if (vm) { vm.$destroy(); vm = null }
  }
  // wujie 会在脚本执行后自动调用 __WUJIE_MOUNT(props) 再挂载；
  // 此处不要兜底 mount()，否则会与 __WUJIE_MOUNT 内的 mount() 产生两个 Vue 实例互相覆盖。
} else {
  mount()
}
