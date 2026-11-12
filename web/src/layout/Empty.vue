<template>
  <!-- 微前端模式专用空 Layout：主应用 MCERP 已提供侧边栏/顶栏，子应用只渲染页面内容 -->
  <section class="wujie-empty-layout" style="min-height:100vh">
    <router-view />
  </section>
</template>

<script>
export default { name: 'WujieEmptyLayout' }
</script>
