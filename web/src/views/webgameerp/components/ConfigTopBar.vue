<template>
  <div class="cfg-topbar">
    <span class="cfg-title">{{ title }}</span>
    <span v-if="file" class="cfg-file">{{ file }}</span>
    <span class="spacer"></span>
    <el-tag v-if="dirty" size="mini" type="warning" effect="plain">● 有未保存修改</el-tag>
    <el-button size="mini" icon="el-icon-refresh" :disabled="loading" @click="$emit('reload')">重新加载</el-button>
    <el-button size="mini" type="primary" icon="el-icon-check" :loading="saving" @click="$emit('save')">保存</el-button>
  </div>
</template>

<script>
/** 配置页统一顶栏：标题 / 文件名 / 脏标记 / 重载 / 保存 */
export default {
  name: 'ConfigTopBar',
  props: {
    title: { type: String, required: true },
    file: { type: String, default: '' },
    dirty: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
    saving: { type: Boolean, default: false }
  }
}
</script>

<style scoped>
.cfg-topbar { height: 52px; background: #fff; border-bottom: 1px solid #e4e7ed; display: flex; align-items: center; padding: 0 16px; gap: 10px; position: sticky; top: 0; z-index: 100; }
.cfg-title { font-size: 15px; font-weight: 600; }
.cfg-file { color: #909399; font-size: 12px; }
.spacer { flex: 1; }
</style>
