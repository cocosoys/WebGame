<template>
  <router-view />
</template>

<script>
/**
 * 目录节点专用的纯路由出口，
 * 只负责把匹配到的子路由继续往下渲染，不带任何 DOM 外壳 / 侧边栏 / 高度样式。
 * 用于 moduleRoutes 中与后端 SoysErpExpansion.menus() 的 dir 对应的中间层级
 * （安全管控 / 插件配置 / 网关），避免嵌套 Layout 叠出多层侧边栏或 min-height 外壳。
 */
export default { name: 'NestedRouterView' }
</script>
