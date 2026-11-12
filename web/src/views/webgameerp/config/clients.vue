<template>
  <div class="app-container">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="多版本客户端"
      style="margin-bottom: 12px"
    >
      <div style="line-height: 20px">
        玩家入口为版本选择页，每个版本挂 <code>/eagler/&lt;id&gt;/</code>。跨 MC 版本（如 1.8.9）需服务器安装 ViaVersion；
        Eaglercraft 协议差异不会在玩家端提示，仅在此管理面提示。
        上传的 HTML 请提前改好 WS 地址（例如 <code>/eagler/&lt;id&gt;</code>）。
      </div>
    </el-alert>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-refresh" size="mini" :loading="loading" @click="getList">刷新</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button icon="el-icon-refresh-left" size="mini" @click="handleRescan">重扫目录</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" icon="el-icon-upload2" size="mini" @click="openUpload">上传客户端</el-button>
      </el-col>
      <el-col :span="10">
        <span class="entry-hint">版本选择页：{{ entryUrl }}</span>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column label="默认" align="center" width="70">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.defaultVersion" type="success" size="mini">默认</el-tag>
          <span v-else style="color: #c0c4cc">—</span>
        </template>
      </el-table-column>
      <el-table-column label="版本 id" align="center" prop="id" width="120" />
      <el-table-column label="名称" align="center" prop="name" min-width="160" show-overflow-tooltip />
      <el-table-column label="MC 版本" align="center" prop="mcVersion" width="100" />
      <el-table-column label="MC 协议" align="center" width="90">
        <template slot-scope="scope">
          <span v-if="scope.row.mcProtocol">{{ scope.row.mcProtocol }}</span>
          <span v-else style="color: #909399">未知</span>
        </template>
      </el-table-column>
      <el-table-column label="Eagler 协议" align="center" width="100">
        <template slot-scope="scope">
          <span v-if="scope.row.eaglerProtocol">V{{ scope.row.eaglerProtocol }}</span>
          <span v-else style="color: #909399">自适应</span>
        </template>
      </el-table-column>
      <el-table-column label="来源" align="center" width="90">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.source === 'builtin'" size="mini" type="info">内置</el-tag>
          <el-tag v-else size="mini">上传</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="大小" align="center" width="100">
        <template slot-scope="scope">{{ formatSize(scope.row.size) }}</template>
      </el-table-column>
      <el-table-column label="上传时间" align="center" width="170">
        <template slot-scope="scope">
          <span v-if="scope.row.uploadedAt">{{ formatTime(scope.row.uploadedAt) }}</span>
          <span v-else style="color: #909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" align="center" width="110">
        <template slot-scope="scope">
          <el-switch
            v-model="scope.row.enabled"
            :disabled="scope.row.source === 'builtin' || scope.row.missing"
            @change="onToggle(scope.row, $event)"
          />
          <el-tag v-if="scope.row.missing" size="mini" type="danger" style="margin-left: 6px">缺失</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="220">
        <template slot-scope="scope">
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
      title="上传客户端版本"
      :visible.sync="uploadVisible"
      width="560px"
      :close-on-click-modal="false"
      @closed="onUploadClosed"
    >
      <el-form ref="form" :model="form" label-width="100px" size="small">
        <el-form-item label="版本 id" required>
          <el-input v-model="form.id" maxlength="32" placeholder="如 1.8.9（同时作为 /eagler/<id>/ 路径）" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="64" placeholder="展示名称，默认同版本 id" />
        </el-form-item>
        <el-form-item label="MC 版本">
          <el-input v-model="form.mcVersion" maxlength="16" placeholder="如 1.8.9" />
        </el-form-item>
        <el-form-item label="MC 协议">
          <el-input-number v-model="form.mcProtocol" :min="0" :max="9999" controls-position="right" />
          <span class="form-hint">0 表示未知</span>
        </el-form-item>
        <el-form-item label="Eagler 协议">
          <el-input-number v-model="form.eaglerProtocol" :min="0" :max="9" controls-position="right" />
          <span class="form-hint">0 表示自适应（3/4）</span>
        </el-form-item>
        <el-form-item label="客户端文件" required>
          <el-upload
            ref="upload"
            drag
            action="#"
            accept=".html"
            :auto-upload="false"
            :limit="1"
            :file-list="fileList"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            style="width: 100%"
          >
            <i class="el-icon-upload" />
            <div class="el-upload__text">将 HTML 文件拖到此处，或<em>点击选择</em></div>
          </el-upload>
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.setDefault" />
        </el-form-item>
        <el-form-item v-if="uploading" label="上传进度">
          <el-progress :percentage="uploadPercent" :stroke-width="14" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" :disabled="uploading" @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="uploading" @click="startUpload">开始上传</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listClients,
  clientImpact,
  setClientEnabled,
  setDefaultClient,
  deleteClient,
  rescanClients,
  initClientUpload,
  uploadClientChunk,
  finishClientUpload,
  abortClientUpload
} from '@/api/erp'

const CHUNK_SIZE = 512 * 1024

export default {
  name: 'WebGameClients',
  data() {
    return {
      loading: false,
      rows: [],
      defaultId: '',
      uploadVisible: false,
      uploading: false,
      uploadPercent: 0,
      file: null,
      fileList: [],
      form: {
        id: '',
        name: '',
        mcVersion: '',
        mcProtocol: 0,
        eaglerProtocol: 0,
        setDefault: false
      }
    }
  },
  computed: {
    entryUrl() {
      return window.location.origin + '/web/plugins/WebGame/eagler/'
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listClients().then(res => {
        const data = res.data || {}
        this.rows = data.rows || []
        this.defaultId = data.defaultId || ''
      }).finally(() => {
        this.loading = false
      })
    },
    onToggle(row, val) {
      if (row.source === 'builtin') {
        row.enabled = true
        this.$message.warning('内置客户端不可关闭')
        return
      }
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
          '关闭客户端版本',
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
        `确认删除客户端版本 ${row.id}？正在使用它的玩家将被断开。`,
        '删除确认',
        { type: 'warning' }
      ).then(() => {
        deleteClient(row.id).then(res => {
          this.$modal.msgSuccess(res.msg || '已删除')
          this.getList()
        })
      }).catch(() => {})
    },
    handleRescan() {
      this.$confirm('将重新扫描磁盘上的客户端目录，是否继续？', '重扫确认', { type: 'warning' })
        .then(() => {
          rescanClients().then(res => {
            this.$modal.msgSuccess(res.msg || '已重新扫描')
            this.getList()
          })
        }).catch(() => {})
    },
    openPage(row) {
      window.open(row.pagePath, '_blank')
    },
    openUpload() {
      this.uploadVisible = true
    },
    onUploadClosed() {
      this.uploading = false
      this.uploadPercent = 0
      this.file = null
      this.fileList = []
      this.form = { id: '', name: '', mcVersion: '', mcProtocol: 0, eaglerProtocol: 0, setDefault: false }
    },
    onFileChange(file, fileList) {
      this.file = file.raw
      this.fileList = fileList.slice(-1)
      if (!this.form.id && file.name) {
        const base = file.name.replace(/\.html?$/i, '')
        this.form.id = base.replace(/[^A-Za-z0-9_.-]/g, '-').replace(/\.{2,}/g, '.').slice(0, 32)
      }
      if (!this.form.mcVersion) {
        this.form.mcVersion = ''
      }
    },
    onFileRemove() {
      this.file = null
      this.fileList = []
    },
    async startUpload() {
      if (!/^[A-Za-z0-9_.-]{1,32}$/.test(this.form.id)) {
        this.$message.error('版本 id 只能包含字母、数字、下划线、点、短横线（1-32 位）')
        return
      }
      if (!this.file) {
        this.$message.error('请选择客户端 HTML 文件')
        return
      }
      this.uploading = true
      this.uploadPercent = 0
      let uploadId = null
      try {
        const initRes = await initClientUpload({
          id: this.form.id,
          name: this.form.name,
          mcVersion: this.form.mcVersion,
          mcProtocol: this.form.mcProtocol,
          eaglerProtocol: this.form.eaglerProtocol
        })
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
        if (this.form.setDefault) {
          await setDefaultClient(this.form.id).catch(() => {})
        }
        this.$modal.msgSuccess(finishRes.msg || '上传完成')
        this.uploadVisible = false
        this.getList()
      } catch (e) {
        if (uploadId) {
          abortClientUpload(uploadId).catch(() => {})
        }
      } finally {
        this.uploading = false
      }
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
    },
    formatSize(size) {
      const n = Number(size) || 0
      if (n <= 0) return '—'
      if (n < 1024) return n + ' B'
      if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
      return (n / 1024 / 1024).toFixed(1) + ' MB'
    },
    formatTime(ms) {
      if (!ms) return '—'
      const d = new Date(ms)
      const p = n => (n < 10 ? '0' + n : '' + n)
      return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' '
        + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
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
.form-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
