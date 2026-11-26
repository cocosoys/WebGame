<template>
  <div class="app-container">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="客户端管理：统一登记 Eagler 客户端包与云游戏客户端包。Eagler 客户端、云游戏设备都从这里选择制品；云游戏“下发更新”只把这里设置的包作为预选项，不再自动静默下发。"
      style="margin-bottom: 12px"
    />

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-refresh" size="mini" :loading="loading" @click="getList">刷新</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" icon="el-icon-upload2" size="mini" @click="openUpload">上传客户端</el-button>
      </el-col>
      <el-col :span="6">
        <el-radio-group v-model="activeType" size="mini">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button label="eagler">Eagler</el-radio-button>
          <el-radio-button label="cloud">云游戏</el-radio-button>
        </el-radio-group>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="displayRows" size="small">
      <el-table-column label="类型" align="center" width="110">
        <template slot-scope="scope">
          <el-tag :type="scope.row.type === 'eagler' ? 'warning' : 'success'" size="mini">
            {{ scope.row.type === 'eagler' ? 'Eagler' : '云游戏' }}
          </el-tag>
          <el-tag v-if="scope.row.source === 'external'" type="info" size="mini" style="margin-left: 4px">外链</el-tag>
          <el-tag v-else-if="scope.row.source === 'builtin'" type="info" size="mini" style="margin-left: 4px">内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="名称" align="center" prop="name" min-width="150" show-overflow-tooltip />
      <el-table-column label="版本" align="center" prop="version" min-width="130" show-overflow-tooltip />
      <el-table-column label="状态" align="center" width="75">
        <template slot-scope="scope">
          <el-tag :type="scope.row.enabled ? 'success' : 'info'" size="mini">
            {{ scope.row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="285">
        <template slot-scope="scope">
          <el-button
            v-if="scope.row.source === 'external' && scope.row.externalUrl"
            type="text"
            size="mini"
            @click="openExternal(scope.row)"
          >打开外链</el-button>
          <el-button
            v-if="scope.row.type === 'cloud' && !scope.row.preselect"
            type="text"
            size="mini"
            :disabled="!scope.row.enabled"
            @click="setPreselect(scope.row)"
          >设为预选</el-button>
          <el-button type="text" size="mini" @click="openEdit(scope.row)">编辑</el-button>
          <el-button
            type="text"
            size="mini"
            :disabled="scope.row.source === 'builtin' || (scope.row.type === 'eagler' && scope.row.referenceCount > 0)"
            @click="toggleEnabled(scope.row)"
          >{{ scope.row.enabled ? '停用' : '启用' }}</el-button>
          <el-button
            type="text"
            size="mini"
            style="color: #f56c6c"
            :disabled="scope.row.source === 'builtin' || (scope.row.type === 'eagler' && scope.row.referenceCount > 0)"
            @click="remove(scope.row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      title="上传客户端"
      :visible.sync="uploadVisible"
      width="580px"
      :close-on-click-modal="false"
      @closed="onUploadDialogClosed"
    >
      <el-form label-width="110px" size="small">
        <el-form-item label="客户端类型">
          <el-radio-group v-model="form.type" :disabled="uploading" @change="onTypeChange">
            <el-radio-button label="eagler">Eagler 客户端</el-radio-button>
            <el-radio-button label="cloud">云游戏客户端</el-radio-button>
          </el-radio-group>
          <div class="el-upload__tip">类型保存后不可修改；换类型请重新上传。</div>
        </el-form-item>

        <el-form-item v-if="form.type === 'eagler'" label="Eagler 来源">
          <el-radio-group v-model="form.eaglerSource" :disabled="uploading" @change="onEaglerSourceChange">
            <el-radio label="file">上传文件</el-radio>
            <el-radio label="external">登记外链</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="form.type === 'cloud' || form.eaglerSource === 'file'" label="客户端文件">
          <el-upload
            ref="upload"
            action="#"
            :auto-upload="false"
            :limit="1"
            :file-list="fileList"
            :accept="accept"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
          >
            <el-button size="small" icon="el-icon-folder-opened">选择文件</el-button>
            <div slot="tip" class="el-upload__tip">{{ uploadTip }}</div>
          </el-upload>
        </el-form-item>

        <el-form-item v-if="form.type === 'eagler' && form.eaglerSource === 'external'" label="外链地址">
          <el-input v-model="form.externalUrl" placeholder="https://example.com/eagler/index.html" />
        </el-form-item>

        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如 1.12.2 整合包 / 1.8.9 Eagler" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="form.version" placeholder="云游戏必填；Eagler 可留空" />
        </el-form-item>
        <el-form-item v-if="form.type === 'cloud'" label="解包目录">
          <el-input v-model="form.targetDir" placeholder="例如 1.12.2-Forge_14.23.5.2864" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item v-if="uploading" label="进度">
          <el-progress :percentage="uploadPercent" />
          <div class="el-upload__tip">{{ uploadStatus }}</div>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button size="small" :disabled="uploading" @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="uploading" @click="startUpload">
          {{ isExternal ? '登记外链' : '开始上传' }}
        </el-button>
      </span>
    </el-dialog>

    <el-dialog title="编辑客户端包" :visible.sync="editVisible" width="520px">
      <el-form label-width="110px" size="small">
        <el-form-item label="类型">
          <el-tag :type="editForm.type === 'eagler' ? 'warning' : 'success'" size="mini">
            {{ editForm.type === 'eagler' ? 'Eagler' : '云游戏' }}
          </el-tag>
          <span class="el-upload__tip">类型不可修改</span>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="版本"><el-input v-model="editForm.version" /></el-form-item>
        <el-form-item v-if="editForm.type === 'cloud'" label="解包目录">
          <el-input v-model="editForm.targetDir" />
        </el-form-item>
        <el-form-item v-if="editForm.type === 'eagler'" label="入口文件">
          <el-input v-model="editForm.entryPath" :disabled="editForm.source === 'builtin'" placeholder="上传时自动识别" />
          <div class="el-upload__tip">入口文件只能指向该包内的 .html/.htm；重新上传会生成新制品。</div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="editForm.notes" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <span slot="footer">
        <el-button size="small" @click="editVisible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="saveEdit">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  listClientPackages,
  updateClientPackage,
  setPreselectClientPackage,
  setClientPackageEnabled,
  deleteClientPackage,
  initClientPackageUpload,
  uploadClientPackageChunk,
  finishClientPackageUpload,
  abortClientPackageUpload,
  registerExternalClientPackage
} from '@/api/erp'

const DEFAULT_TARGET = '1.12.2-Forge_14.23.5.2864'

export default {
  name: 'WebGameClientCenterPackages',
  data() {
    return {
      loading: false,
      saving: false,
      rows: [],
      activeType: 'all',
      uploadVisible: false,
      editVisible: false,
      uploadFile: null,
      fileList: [],
      uploading: false,
      uploadPercent: 0,
      uploadStatus: '',
      uploadId: '',
      form: this.emptyForm(),
      editForm: {}
    }
  },
  computed: {
    displayRows() {
      if (this.activeType === 'all') return this.rows
      return this.rows.filter(r => r.type === this.activeType)
    },
    accept() {
      return this.form.type === 'eagler' ? '.html,.htm,.zip' : '.zip'
    },
    uploadTip() {
      return this.form.type === 'eagler'
        ? '支持单个 .html/.htm，或包含入口的 .zip；zip 会优先识别 index.html。'
        : '整份 .minecraft 的 .zip，根目录或唯一一层子目录含 mods/ 或 versions/。'
    },
    isExternal() {
      return this.form.type === 'eagler' && this.form.eaglerSource === 'external'
    }
  },
  created() {
    this.getList()
  },
  methods: {
    emptyForm() {
      return {
        type: 'eagler',
        eaglerSource: 'file',
        name: '',
        version: '',
        targetDir: DEFAULT_TARGET,
        externalUrl: '',
        notes: ''
      }
    },
    getList() {
      this.loading = true
      listClientPackages().then(res => {
        const data = res.data || {}
        this.rows = data.rows || data.packages || []
      }).finally(() => {
        this.loading = false
      })
    },
    openUpload() {
      this.form = this.emptyForm()
      this.uploadFile = null
      this.fileList = []
      this.uploadPercent = 0
      this.uploadStatus = ''
      this.uploading = false
      this.uploadVisible = true
      this.$nextTick(() => { if (this.$refs.upload) this.$refs.upload.clearFiles() })
    },
    onTypeChange() {
      this.uploadFile = null
      this.fileList = []
      if (this.form.type === 'cloud') {
        this.form.eaglerSource = 'file'
        this.form.version = this.form.version || DEFAULT_TARGET
        this.form.targetDir = this.form.targetDir || DEFAULT_TARGET
      }
      this.$nextTick(() => { if (this.$refs.upload) this.$refs.upload.clearFiles() })
    },
    onEaglerSourceChange() {
      this.uploadFile = null
      this.fileList = []
      this.$nextTick(() => { if (this.$refs.upload) this.$refs.upload.clearFiles() })
    },
    onFileChange(file, fileList) {
      this.uploadFile = file.raw
      this.fileList = fileList.slice(-1)
      if (!this.form.name) this.form.name = file.name.replace(/\.(zip|html?|htm)$/i, '')
    },
    onFileRemove() {
      this.uploadFile = null
      this.fileList = []
    },
    onUploadDialogClosed() {
      if (this.uploading && this.uploadId) {
        abortClientPackageUpload(this.uploadId)
      }
    },
    startUpload() {
      if (this.isExternal) {
        return this.submitExternal()
      }
      if (!this.uploadFile) {
        this.$message.warning('请选择客户端文件')
        return
      }
      if (this.form.type === 'cloud' && !this.form.targetDir) {
        this.$message.warning('解包目录不能为空')
        return
      }
      this.uploading = true
      this.uploadPercent = 0
      this.uploadStatus = '初始化…'
      const file = this.uploadFile
      initClientPackageUpload({
        type: this.form.type,
        fileName: file.name,
        size: file.size,
        name: this.form.name,
        version: this.form.version,
        targetDir: this.form.type === 'cloud' ? this.form.targetDir : ''
      }).then(res => {
        const data = res.data || {}
        if (!data.uploadId) throw new Error('没有拿到 uploadId')
        this.uploadId = data.uploadId
        const chunkSize = data.chunkSize || 512 * 1024
        const total = Math.ceil(file.size / chunkSize) || 1
        let index = 0
        const next = () => {
          if (index >= total) {
            this.uploadStatus = '合并并校验…'
            return finishClientPackageUpload(this.uploadId).then(() => {
              this.uploadId = ''
              this.$message.success('客户端包已上传')
              this.uploadVisible = false
              this.getList()
            })
          }
          const start = index * chunkSize
          const end = Math.min(file.size, start + chunkSize)
          return this.readBase64(file.slice(start, end)).then(b64 => {
            return uploadClientPackageChunk(this.uploadId, index, b64).then(() => {
              index++
              this.uploadPercent = Math.min(99, Math.floor(index * 100 / total))
              this.uploadStatus = `已上传 ${index}/${total} 块`
              return next()
            })
          })
        }
        return next()
      }).catch(err => {
        this.$message.error((err && err.message) || '上传失败')
        if (this.uploadId) abortClientPackageUpload(this.uploadId)
      }).finally(() => {
        this.uploading = false
      })
    },
    submitExternal() {
      if (!this.form.externalUrl) {
        this.$message.warning('请填写外链地址')
        return
      }
      if (!this.form.name) {
        this.$message.warning('请填写名称')
        return
      }
      this.uploading = true
      registerExternalClientPackage({
        name: this.form.name,
        version: this.form.version,
        externalUrl: this.form.externalUrl,
        notes: this.form.notes,
        enabled: true
      }).then(() => {
        this.$message.success('外链客户端已登记')
        this.uploadVisible = false
        this.getList()
      }).finally(() => {
        this.uploading = false
      })
    },
    readBase64(blob) {
      return new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => {
          const s = String(reader.result || '')
          resolve(s.substring(s.indexOf(',') + 1))
        }
        reader.onerror = reject
        reader.readAsDataURL(blob)
      })
    },
    setPreselect(row) {
      setPreselectClientPackage(row.id).then(() => {
        this.$message.success('已设为下发预选')
        this.getList()
      })
    },
    toggleEnabled(row) {
      const enabled = !row.enabled
      setClientPackageEnabled(row.id, enabled).then(() => {
        this.$message.success(enabled ? '已启用' : '已停用')
        this.getList()
      })
    },
    remove(row) {
      this.$confirm(`删除客户端包「${row.name}」？文件会一起删除，已引用它的客户端会失效。`, '确认', { type: 'warning' }).then(() => {
        return deleteClientPackage(row.id)
      }).then(() => {
        this.$message.success('已删除')
        this.getList()
      }).catch(() => {})
    },
    openEdit(row) {
      this.editForm = {
        id: row.id,
        type: row.type,
        source: row.source,
        name: row.name,
        version: row.version,
        targetDir: row.targetDir,
        entryPath: row.entryPath,
        notes: row.notes || ''
      }
      this.editVisible = true
    },
    saveEdit() {
      this.saving = true
      const payload = {
        name: this.editForm.name,
        version: this.editForm.version,
        notes: this.editForm.notes
      }
      if (this.editForm.type === 'cloud') payload.targetDir = this.editForm.targetDir
      if (this.editForm.type === 'eagler' && this.editForm.source !== 'builtin') {
        payload.entryPath = this.editForm.entryPath
      }
      updateClientPackage(this.editForm.id, payload).then(() => {
        this.$message.success('已保存')
        this.editVisible = false
        this.getList()
      }).finally(() => {
        this.saving = false
      })
    },
    openExternal(row) {
      if (row.externalUrl) window.open(row.externalUrl, '_blank')
    }
  }
}
</script>
