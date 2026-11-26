<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-refresh" size="mini" :loading="loading" @click="getList">刷新</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" icon="el-icon-plus" size="mini" @click="openCreate">新增客户端</el-button>
      </el-col>
      <el-col :span="7">
        <span class="entry-hint">允许玩家自选版本：</span>
        <el-switch
          v-model="allowPlayerSelect"
          :disabled="allowSelectSaving"
          @change="onAllowSelectChange"
        />
        <span class="entry-hint" style="margin-left: 8px">关闭后 /eagler/ 直接进入默认版本</span>
      </el-col>
      <el-col :span="9">
        <span class="entry-hint">玩家入口：{{ entryUrl }}</span>
      </el-col>
    </el-row>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="客户端路径改为从「客户端」→「客户端管理」登记的 Eagler 制品中选择；这里不再手输路径，也不再单独上传文件。"
      style="margin-bottom: 12px"
    />

    <el-table ref="table" v-loading="loading" :data="rows" size="small">
      <el-table-column label="排序" align="center" width="60">
        <template slot-scope="scope">
          <i class="el-icon-rank drag-handle" title="拖拽调整顺序" />
        </template>
      </el-table-column>
      <el-table-column label="默认" align="center" width="70">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.defaultVersion" type="success" size="mini">默认</el-tag>
          <span v-else style="color: #c0c4cc">—</span>
        </template>
      </el-table-column>
      <el-table-column label="客户端 id" align="center" prop="id" width="120" />
      <el-table-column label="名称" align="center" prop="name" min-width="150" show-overflow-tooltip />
      <el-table-column label="客户端包" align="center" min-width="180" show-overflow-tooltip>
        <template slot-scope="scope">
          <div>{{ scope.row.packageName || '—' }}</div>
          <div class="form-hint">{{ scope.row.packageId || '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="入口 / 路径" align="center" min-width="240" show-overflow-tooltip>
        <template slot-scope="scope">{{ scope.row.path }}</template>
      </el-table-column>
      <el-table-column label="MC 版本" align="center" min-width="160">
        <template slot-scope="scope">
          <template v-if="scope.row.mcVersions && scope.row.mcVersions.length">
            <el-tag
              v-for="v in scope.row.mcVersions"
              :key="v.version"
              size="mini"
              :title="'协议号 ' + v.protocol"
              style="margin: 1px 2px"
            >{{ v.version }}</el-tag>
          </template>
          <span v-else style="color: #909399">未指定</span>
        </template>
      </el-table-column>
      <el-table-column label="Eagler 协议" align="center" width="100">
        <template slot-scope="scope">
          <span v-if="scope.row.eaglerProtocol">V{{ scope.row.eaglerProtocol }}</span>
          <span v-else style="color: #909399">自适应</span>
        </template>
      </el-table-column>
      <el-table-column label="来源" align="center" width="80">
        <template slot-scope="scope">
          <el-tag size="mini" :type="sourceTagType(scope.row.source)">{{ sourceLabel(scope.row.source) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" align="center" width="110">
        <template slot-scope="scope">
          <el-switch
            v-model="scope.row.enabled"
            :disabled="scope.row.missing"
            @change="onToggle(scope.row, $event)"
          />
          <el-tag v-if="scope.row.missing" size="mini" type="danger" style="margin-left: 6px">缺失</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="250">
        <template slot-scope="scope">
          <el-button type="text" size="mini" @click="openEdit(scope.row)">编辑</el-button>
          <el-button
            type="text"
            size="mini"
            :disabled="scope.row.defaultVersion || !scope.row.enabled || scope.row.missing"
            @click="handleSetDefault(scope.row)"
          >设为默认</el-button>
          <el-button type="text" size="mini" @click="openPage(scope.row)">打开页面</el-button>
          <el-button
            v-if="scope.row.source !== 'builtin'"
            type="text"
            size="mini"
            style="color: #f56c6c"
            @click="handleDelete(scope.row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      :title="editEditing ? '编辑 eagler 客户端' : '新增 eagler 客户端'"
      :visible.sync="editVisible"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form ref="editForm" :model="editForm" label-width="100px" size="small">
        <el-form-item label="客户端 id" required>
          <el-input v-model="editForm.id" maxlength="32" :disabled="editEditing" placeholder="如 1.8.9（同时作为 /eagler/<id>/ 路径）" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="editForm.name" maxlength="64" placeholder="展示名称，默认同客户端 id" />
        </el-form-item>
        <el-form-item label="客户端包" required>
          <el-select
            v-model="editForm.packageId"
            filterable
            placeholder="从客户端管理中选择 Eagler 制品"
            style="width: 100%"
          >
            <el-option
              v-for="pkg in eaglerPackages"
              :key="pkg.id"
              :label="packageLabel(pkg)"
              :value="pkg.id"
              :disabled="!pkg.enabled"
            />
          </el-select>
          <div class="form-hint">
            只列出「客户端管理」中 type=Eagler 的制品，路径、入口、SHA-256 由制品自动解析。
            <router-link to="/webgameerp/client-center/packages">去客户端管理上传</router-link>
          </div>
        </el-form-item>
        <el-form-item label="MC 版本">
          <el-select
            v-model="editForm.mcVersions"
            multiple
            filterable
            clearable
            placeholder="选择该客户端支持的 Minecraft 版本（可多选）"
            style="width: 100%"
          >
            <el-option
              v-for="opt in mcVersionOptions"
              :key="opt.version"
              :label="opt.version + ' (' + opt.protocol + ')'"
              :value="opt.version"
            />
          </el-select>
          <div class="form-hint">协议号由版本自动带出，仅作记录，不参与联机协商</div>
        </el-form-item>
        <el-form-item label="Eagler 协议">
          <el-select v-model="editForm.eaglerProtocol" style="width: 100%">
            <el-option label="自适应（推荐）" :value="0" />
            <el-option label="V3（旧版 eagler）" :value="3" />
            <el-option label="V4（新版 eagler）" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="editVisible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="editSaving" @click="submitEdit">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listClients,
  listMcVersions,
  clientImpact,
  setClientEnabled,
  setDefaultClient,
  deleteClient,
  reorderClients,
  setClientAllowSelect,
  createClient,
  updateClient,
  listClientPackages
} from '@/api/erp'
import Sortable from 'sortablejs'

const emptyEditForm = () => ({
  id: '',
  name: '',
  packageId: '',
  mcVersions: [],
  eaglerProtocol: 0,
  enabled: true,
  source: ''
})

export default {
  name: 'WebGameClients',
  data() {
    return {
      loading: false,
      rows: [],
      mcVersionOptions: [],
      eaglerPackages: [],
      defaultId: '',
      allowPlayerSelect: true,
      allowSelectSaving: false,
      sortable: null,
      editVisible: false,
      editEditing: false,
      editSaving: false,
      editForm: emptyEditForm()
    }
  },
  computed: {
    entryUrl() {
      return window.location.origin + '/web/plugins/WebGame/eagler/'
    }
  },
  created() {
    this.getList()
    this.getMcVersions()
    this.getPackages()
  },
  beforeDestroy() {
    this.destroySortable()
  },
  methods: {
    getList() {
      this.loading = true
      listClients().then(res => {
        const data = res.data || {}
        this.destroySortable()
        this.rows = data.rows || []
        this.defaultId = data.defaultId || ''
        this.allowPlayerSelect = data.allowPlayerSelect !== false
        this.$nextTick(this.initSortable)
      }).finally(() => {
        this.loading = false
      })
    },
    getMcVersions() {
      listMcVersions().then(res => {
        this.mcVersionOptions = res.data || []
      })
    },
    getPackages() {
      listClientPackages('eagler').then(res => {
        const data = res.data || {}
        this.eaglerPackages = data.rows || data.packages || []
      })
    },
    packageLabel(pkg) {
      const version = pkg.version ? ` · ${pkg.version}` : ''
      const entry = pkg.entryPath ? ` · ${pkg.entryPath}` : ''
      return `${pkg.name}${version}${entry}${pkg.enabled ? '' : '（已停用）'}`
    },
    initSortable() {
      this.destroySortable()
      const table = this.$refs.table
      const tbody = table && table.$el.querySelector('.el-table__body-wrapper tbody')
      if (!tbody || this.rows.length < 2) {
        return
      }
      this.sortable = Sortable.create(tbody, {
        animation: 150,
        handle: '.drag-handle',
        filter: '.el-switch, .el-button, .el-input',
        onEnd: evt => this.onSortEnd(evt)
      })
    },
    destroySortable() {
      if (this.sortable) {
        this.sortable.destroy()
        this.sortable = null
      }
    },
    onSortEnd(evt) {
      if (evt.oldIndex === evt.newIndex) {
        return
      }
      const list = this.rows.slice()
      const moved = list.splice(evt.oldIndex, 1)[0]
      list.splice(evt.newIndex, 0, moved)
      const ids = list.map(r => r.id)
      reorderClients(ids).then(res => {
        this.$modal.msgSuccess(res.msg || '排序已保存')
      }).catch(() => {
      }).finally(() => {
        this.getList()
      })
    },
    onAllowSelectChange(val) {
      const apply = () => {
        this.allowSelectSaving = true
        setClientAllowSelect(val).then(res => {
          this.$modal.msgSuccess(res.msg || '设置成功')
          this.getList()
        }).catch(() => {
          this.allowPlayerSelect = !val
        }).finally(() => {
          this.allowSelectSaving = false
        })
      }
      if (val) {
        apply()
        return
      }
      this.$confirm(
        '关闭后玩家访问 /eagler/ 将直接进入默认版本，其它客户端页面不可访问；已在线玩家不受影响。是否继续？',
        '禁止玩家自选版本',
        { type: 'warning', confirmButtonText: '禁止自选', cancelButtonText: '取消' }
      ).then(apply).catch(() => {
        this.allowPlayerSelect = true
      })
    },
    openCreate() {
      this.editEditing = false
      this.editForm = emptyEditForm()
      this.editForm.packageId = (this.eaglerPackages.find(p => p.enabled) || {}).id || ''
      this.editVisible = true
    },
    openEdit(row) {
      this.editEditing = true
      this.editForm = {
        id: row.id,
        name: row.name,
        packageId: row.packageId || '',
        mcVersions: (row.mcVersions || []).map(v => v.version),
        eaglerProtocol: row.eaglerProtocol,
        enabled: row.enabled,
        source: row.source
      }
      this.editVisible = true
    },
    async submitEdit() {
      const creating = !this.editEditing
      if (creating && !/^[A-Za-z0-9_.-]{1,32}$/.test(this.editForm.id)) {
        this.$message.error('客户端 id 只能包含字母、数字、下划线、点、短横线（1-32 位）')
        return
      }
      if (!this.editForm.packageId) {
        this.$message.error('请选择一个 Eagler 客户端包')
        return
      }
      this.editSaving = true
      try {
        const payload = {
          id: this.editForm.id,
          name: this.editForm.name,
          packageId: this.editForm.packageId,
          mcVersions: this.editForm.mcVersions,
          eaglerProtocol: this.editForm.eaglerProtocol,
          enabled: this.editForm.enabled
        }
        const res = creating
          ? await createClient(payload)
          : await updateClient(this.editForm.id, payload)
        this.$modal.msgSuccess(res.msg || '已保存')
        this.editVisible = false
        this.getList()
      } finally {
        this.editSaving = false
      }
    },
    sourceLabel(source) {
      return { builtin: '内置', uploaded: '上传', external: '外链', local: '本地' }[source] || source || '—'
    },
    sourceTagType(source) {
      return { builtin: 'info', uploaded: '', external: 'warning', local: 'success' }[source] || ''
    },
    onToggle(row, val) {
      if (val) {
        this.applyEnabled(row, true, false)
        return
      }
      clientImpact(row.id).then(res => {
        const total = (res.data && res.data.total) || 0
        if (total <= 0) {
          this.applyEnabled(row, false, false)
          return
        }
        this.$confirm(
          `当前有 ${total} 个玩家正在使用 ${row.id} 客户端，关闭后将强制断开这些连接，是否继续？`,
          '关闭 eagler 客户端',
          { type: 'warning', confirmButtonText: '关闭并断开', cancelButtonText: '取消' }
        ).then(() => {
          this.applyEnabled(row, false, true)
        }).catch(() => {
          row.enabled = true
        })
      }).catch(() => {
        row.enabled = true
      })
    },
    applyEnabled(row, enabled, kick) {
      setClientEnabled(row.id, enabled, kick).then(res => {
        this.$modal.msgSuccess(res.msg || '操作成功')
        this.getList()
      }).catch(() => {
        row.enabled = !enabled
      })
    },
    handleSetDefault(row) {
      setDefaultClient(row.id).then(res => {
        this.$modal.msgSuccess(res.msg || '已设为默认')
        this.getList()
      })
    },
    handleDelete(row) {
      this.$confirm(
        `确认删除 eagler 客户端 ${row.id}？正在使用它的玩家将被断开。`,
        '删除确认',
        { type: 'warning' }
      ).then(() => {
        deleteClient(row.id).then(res => {
          this.$modal.msgSuccess(res.msg || '已删除')
          this.getList()
        })
      }).catch(() => {})
    },
    openPage(row) {
      window.open(row.pagePath, '_blank')
    }
  }
}
</script>

<style scoped>
.mb8 {
  margin-bottom: 8px;
}
.entry-hint {
  font-size: 12px;
  color: #909399;
  line-height: 28px;
}
.drag-handle {
  cursor: move;
  color: #909399;
  font-size: 16px;
}
.drag-handle:hover {
  color: #409eff;
}
.form-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
