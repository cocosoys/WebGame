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
      <el-table-column label="路径" align="center" prop="path" min-width="240" show-overflow-tooltip />
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
        <el-form-item label="客户端路径" required>
          <el-input
            v-model="editForm.path"
            :disabled="editEditing && editForm.source === 'builtin'"
            placeholder="https://example.com/client.html 或 clients/1.8.9/index.html"
          />
          <div class="form-hint">
            支持 URL、本地文件（相对插件数据目录 <code>plugins/WebGame</code> / 服务器根目录）与绝对路径；
            也可在下方上传文件后自动回填
          </div>
        </el-form-item>
        <el-form-item v-if="!(editEditing && editForm.source === 'builtin')" label="上传文件">
          <el-upload
            ref="upload"
            drag
            action="#"
            accept=".html,.htm,.zip"
            :auto-upload="false"
            :limit="1"
            :disabled="editSaving"
            :file-list="fileList"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            style="width: 100%"
          >
            <i class="el-icon-upload" />
            <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
          </el-upload>
          <div class="form-hint">
            只可上传一个文件，保存时写入 <code>clients/&lt;id&gt;/</code>（先清空该目录）并回填客户端路径；
            上传 .zip 会自动解压，且只回填目录前缀，需再补充具体入口文件
          </div>
        </el-form-item>
        <el-form-item v-if="uploading" label="上传进度">
          <el-progress :percentage="uploadPercent" :stroke-width="14" />
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
  initClientUpload,
  uploadClientChunk,
  finishClientUpload,
  abortClientUpload
} from '@/api/erp'
import Sortable from 'sortablejs'

const CHUNK_SIZE = 512 * 1024

const emptyEditForm = () => ({
  id: '',
  name: '',
  path: '',
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
      defaultId: '',
      allowPlayerSelect: true,
      allowSelectSaving: false,
      sortable: null,
      editVisible: false,
      editEditing: false,
      editSaving: false,
      editForm: emptyEditForm(),
      uploading: false,
      uploadPercent: 0,
      file: null,
      fileList: []
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
      this.editVisible = true
      this.$nextTick(this.clearFile)
    },
    openEdit(row) {
      this.editEditing = true
      this.editForm = {
        id: row.id,
        name: row.name,
        path: row.path,
        mcVersions: (row.mcVersions || []).map(v => v.version),
        eaglerProtocol: row.eaglerProtocol,
        enabled: row.enabled,
        source: row.source
      }
      this.editVisible = true
      this.$nextTick(this.clearFile)
    },
    async submitEdit() {
      const creating = !this.editEditing
      const builtin = this.editEditing && this.editForm.source === 'builtin'
      if (creating && !/^[A-Za-z0-9_.-]{1,32}$/.test(this.editForm.id)) {
        this.$message.error('客户端 id 只能包含字母、数字、下划线、点、短横线（1-32 位）')
        return
      }
      if (!builtin && !this.editForm.path && !this.file) {
        this.$message.error('请填写客户端路径或上传客户端文件')
        return
      }
      this.editSaving = true
      try {
        let path = this.editForm.path
        if (this.file) {
          const result = await this.uploadFile()
          if (!result) {
            return
          }
          if (result.archive) {
            // 压缩包：只回填目录前缀，等待管理员补充具体入口文件
            this.editForm.path = result.path
            this.clearFile()
            this.$message.warning('压缩包已解压，请补充具体入口文件路径（如 ' + result.path + 'index.html）后再次保存')
            return
          }
          path = result.path
          this.editForm.path = path
          this.clearFile()
        }
        if (!path) {
          this.$message.error('请填写客户端路径或上传客户端文件')
          return
        }
        if (path.endsWith('/') || path.endsWith('\\')) {
          this.$message.error('请补充具体入口文件路径，例如 ' + path + 'index.html')
          return
        }
        const payload = {
          id: this.editForm.id,
          name: this.editForm.name,
          path,
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
    async uploadFile() {
      const fileName = this.file.name || ''
      if (!/\.(html?|zip)$/i.test(fileName)) {
        this.$message.error('仅支持上传 .html/.htm/.zip 文件')
        return null
      }
      this.uploading = true
      this.uploadPercent = 0
      let uploadId = null
      try {
        const initRes = await initClientUpload({ id: this.editForm.id, fileName })
        uploadId = initRes.data.uploadId
        const chunkSize = initRes.data.chunkSize || CHUNK_SIZE
        const total = Math.max(1, Math.ceil(this.file.size / chunkSize))
        for (let i = 0; i < total; i++) {
          const blob = this.file.slice(i * chunkSize, Math.min(this.file.size, (i + 1) * chunkSize))
          const base64 = await this.readBase64(blob)
          await uploadClientChunk(uploadId, i, base64)
          this.uploadPercent = Math.floor(((i + 1) / total) * 100)
        }
        const finishRes = await finishClientUpload(uploadId)
        uploadId = null
        this.uploadPercent = 100
        return finishRes.data || {}
      } catch (e) {
        if (uploadId) {
          abortClientUpload(uploadId).catch(() => {})
        }
        return null
      } finally {
        this.uploading = false
      }
    },
    clearFile() {
      this.file = null
      this.fileList = []
      if (this.$refs.upload) {
        this.$refs.upload.clearFiles()
      }
    },
    sourceLabel(source) {
      return { builtin: '内置', uploaded: '上传', url: 'URL', local: '本地' }[source] || source || '—'
    },
    sourceTagType(source) {
      return { builtin: 'info', uploaded: '', url: 'warning', local: 'success' }[source] || ''
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
    },
    onFileChange(file, fileList) {
      if (!/\.(html?|zip)$/i.test(file.name || '')) {
        this.$message.error('仅支持上传 .html/.htm/.zip 文件')
        this.clearFile()
        return
      }
      this.file = file.raw
      this.fileList = fileList.slice(-1)
    },
    onFileRemove() {
      this.file = null
      this.fileList = []
    },
    readBase64(blob) {
      return new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => {
          const s = String(reader.result || '')
          const idx = s.indexOf(',')
          resolve(idx >= 0 ? s.substring(idx + 1) : s)
        }
        reader.onerror = reject
        reader.readAsDataURL(blob)
      })
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
