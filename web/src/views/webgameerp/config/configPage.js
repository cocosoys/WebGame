import { loadConfigFile, saveConfigFile } from '@/api/erp'

/**
 * 插件配置页通用 mixin：load / save / dirty 追踪。
 *
 * 使用页面在 data() 中提供 fileId（WebGameConfigFile.id）即可。
 */
export default {
  data() {
    return {
      model: {},
      original: '',
      loading: false,
      saving: false
    }
  },
  computed: {
    dirty() {
      return JSON.stringify(this.model) !== this.original
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      loadConfigFile(this.fileId).then(res => {
        this.model = res.data || {}
        this.original = JSON.stringify(this.model)
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    save() {
      this.saving = true
      saveConfigFile(this.fileId, this.model).then(res => {
        this.saving = false
        this.original = JSON.stringify(this.model)
        this.$modal.msgSuccess('保存成功')
        const tip = res.data && res.data.tip
        if (tip) this.$alert(tip, '提示', { confirmButtonText: '知道了' })
      }).catch(() => { this.saving = false })
    },
    reload() {
      const doReload = () => this.load()
      if (this.dirty) {
        this.$confirm('有未保存的修改，重新加载将丢弃，确定？', '提示', { type: 'warning' }).then(doReload).catch(() => {})
      } else {
        doReload()
      }
    }
  }
}
