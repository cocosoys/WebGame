<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-refresh" size="mini" :loading="loading" @click="getList">刷新</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" icon="el-icon-plus" size="mini" @click="openCreate">新增客户端</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          icon="el-icon-refresh-right"
          size="mini"
          :loading="refreshingAll"
          @click="handleRefreshAll"
        >全部刷新</el-button>
      </el-col>
      <el-col :span="7">
        <span class="entry-hint">允许玩家自选客户端：</span>
        <el-switch
          v-model="allowPlayerSelect"
          :disabled="allowSelectSaving"
          @change="onAllowSelectChange"
        />
        <span class="entry-hint" style="margin-left: 8px">关闭后 /cloud/ 直接进入默认客户端</span>
      </el-col>
      <el-col :span="9">
        <span class="entry-hint">玩家入口：{{ entryUrl }}</span>
      </el-col>
      <el-col :span="24">
        <span class="entry-hint">
          URL 客户端默认由 MC 端口同端口转播（/cloud/&lt;id&gt;/live/），玩家只需访问 MC 端口，不必为云主机单独做端口映射；
          关掉 cloud.same-port-relay 后回退为直连跳转。
        </span>
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
      <el-table-column label="id" align="center" prop="id" width="90" />
      <el-table-column label="名称" align="center" prop="name" min-width="150" show-overflow-tooltip />
      <el-table-column label="设备地址" align="center" min-width="170">
        <template slot-scope="scope">
          <span v-if="scope.row.address">{{ (scope.row.tls ? 'https://' : 'http://') + scope.row.address }}</span>
          <span v-else style="color: #909399">未上报</span>
        </template>
      </el-table-column>
      <el-table-column label="运行状态" align="center" width="130">
        <template slot-scope="scope">
          <el-tag size="mini" :type="runtimeTagType(scope.row.status)">{{ runtimeLabel(scope.row.status) }}</el-tag>
          <el-tag v-if="scope.row.busy" size="mini" type="warning" style="margin-left: 4px">占用</el-tag>
          <div class="runtime-hint">
            <span v-if="scope.row.latencyMs">延迟 {{ scope.row.latencyMs }}ms</span>
            <span v-else-if="scope.row.version">v{{ scope.row.version }}</span>
            <span v-else>—</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="健康原因" align="center" min-width="200">
        <template slot-scope="scope">
          <el-tooltip v-if="scope.row.healthReason" :content="scope.row.healthReason" placement="top">
            <span class="reason">{{ scope.row.healthReason }}</span>
          </el-tooltip>
          <span v-else style="color: #909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="别名（门牌）" align="center" min-width="180">
        <template slot-scope="scope">
          <template v-if="scope.row.aliases && scope.row.aliases.length">
            <a
              v-for="a in scope.row.aliases"
              :key="a"
              class="alias-link"
              :href="rootEntry(a)"
              target="_blank"
            >/{{ a }}</a>
          </template>
          <span v-else style="color: #909399">未配置</span>
        </template>
      </el-table-column>
      <el-table-column label="池" align="center" width="110">
        <template slot-scope="scope">
          <span v-if="scope.row.pool">{{ scope.row.pool }}</span>
          <span v-else style="color: #909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="客户端版本" align="center" min-width="210">
        <template slot-scope="scope">
          <div class="ver-line">
            <span>实际：</span>
            <span v-if="scope.row.version">{{ scope.row.version }}</span>
            <span v-else style="color: #909399">未上报</span>
          </div>
          <div class="ver-line">
            <span>期望：</span>
            <span v-if="scope.row.expectedVersion">{{ scope.row.expectedVersion }}</span>
            <span v-else style="color: #909399">未配置</span>
            <el-tag
              v-if="scope.row.expectedVersion"
              size="mini"
              :type="scope.row.versionMatch ? 'success' : 'warning'"
              style="margin-left: 4px"
            >{{ scope.row.versionMatch ? '一致' : '不一致' }}</el-tag>
          </div>
          <div v-if="scope.row.updateState" class="runtime-hint">
            <span v-if="scope.row.updateState === 'updating'" style="color: #e6a23c">正在更新…</span>
            <el-tooltip v-else :content="scope.row.updateError || '更新失败'" placement="top">
              <span style="color: #f56c6c">更新失败</span>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="最近成功" align="center" width="150">
        <template slot-scope="scope">
          <span v-if="scope.row.lastOk">{{ scope.row.lastOk }}</span>
          <span v-else style="color: #909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="接入方式" align="center" min-width="260">
        <template slot-scope="scope">
          <template v-if="scope.row.source === 'url'">
            <el-tooltip
              v-if="scope.row.livePath"
              :content="'同端口转播：' + scope.row.livePath"
              placement="top"
            >
              <el-tag size="mini" type="success">同端口</el-tag>
            </el-tooltip>
            <el-tooltip v-else content="未走同端口转播，跳转到设备地址" placement="top">
              <el-tag size="mini" type="warning">直连</el-tag>
            </el-tooltip>
            <span class="live-path">{{ scope.row.livePath || '—' }}</span>
            <el-button
              v-if="scope.row.livePath"
              type="text"
              size="mini"
              icon="el-icon-document-copy"
              @click="copyText(liveUrl(scope.row))"
            >复制</el-button>
          </template>
          <el-tag v-else size="mini" type="info">本地页面</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="路径" align="center" prop="path" min-width="240" show-overflow-tooltip />
      <el-table-column label="MC 版本" align="center" min-width="120">
        <template slot-scope="scope">
          <span v-if="scope.row.mcVersion">{{ scope.row.mcVersion }}</span>
          <span v-else style="color: #909399">未填写</span>
        </template>
      </el-table-column>
      <el-table-column label="来源" align="center" width="80">
        <template slot-scope="scope">
          <el-tag size="mini" :type="sourceTagType(scope.row.source)">{{ sourceLabel(scope.row.source) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" align="center" width="110">
        <template slot-scope="scope">
          <el-switch
            v-model="scope.row.enabled"
            :disabled="scope.row.missing"
            @change="onToggle(scope.row, $event)"
          />
          <el-tag v-if="scope.row.missing" size="mini" type="danger" style="margin-left: 6px">缺失</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="400">
        <template slot-scope="scope">
          <el-button
            type="text"
            size="mini"
            :loading="refreshingId === scope.row.id"
            :disabled="!scope.row.address"
            @click="handleRefresh(scope.row)"
          >刷新</el-button>
          <el-button
            type="text"
            size="mini"
            :loading="pushingId === scope.row.id"
            :disabled="scope.row.source !== 'url' || !scope.row.address || scope.row.busy"
            @click="handlePushUpdate(scope.row)"
          >下发更新</el-button>
          <el-button type="text" size="mini" @click="openEdit(scope.row)">编辑</el-button>
          <el-button
            type="text"
            size="mini"
            :disabled="scope.row.defaultVersion || !scope.row.enabled || scope.row.missing"
            @click="handleSetDefault(scope.row)"
          >设为默认</el-button>
          <el-button type="text" size="mini" @click="handleUpdateKey(scope.row)">密钥</el-button>
          <el-button type="text" size="mini" @click="openPage(scope.row)">打开页面</el-button>
          <el-button type="text" size="mini" @click="copyText(liveUrl(scope.row))">复制入口</el-button>
          <el-button
            type="text"
            size="mini"
            style="color: #f56c6c"
            @click="handleDelete(scope.row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      :title="editEditing ? '编辑云游戏客户端' : '新增云游戏客户端'"
      :visible.sync="editVisible"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form ref="editForm" :model="editForm" label-width="90px" size="small">
        <el-form-item label="id">
          <el-input :value="editEditing ? editForm.id : '保存后自动生成'" disabled />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="editForm.name" maxlength="64" placeholder="展示名称" />
        </el-form-item>
        <el-form-item label="URL" required>
          <el-input
            v-model="editForm.path"
            placeholder="https://example.com/index.html"
          />
          <div class="form-hint">
            URL 客户端由 MC 端口同端口转播，玩家访问 {{ entryUrl }}&lt;id&gt;/ 即可，无需为设备单独开端口。
          </div>
        </el-form-item>
        <el-form-item label="MC 版本">
          <el-input v-model="editForm.mcVersion" maxlength="32" placeholder="自由填写，如 1.12.2 / 1.8.9" />
        </el-form-item>
        <el-form-item label="别名">
          <el-input
            v-model="editForm.aliasesText"
            maxlength="200"
            placeholder="如 测试服务器, test（逗号分隔；ASCII 不区分大小写）"
          />
          <div class="form-hint">
            每个别名就是一个短地址 /cloud/&lt;别名&gt;，玩家不用记 ID；全局唯一，不能与保留字冲突。
          </div>
        </el-form-item>
        <el-form-item label="池">
          <el-input v-model="editForm.pool" maxlength="24" placeholder="留空 = 不参与池匹配" />
          <div class="form-hint">
            同池的机器会共享入口 /cloud/pool/&lt;池名&gt;，玩家点进去自动挑一台空闲的。
          </div>
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
  listCloudClients,
  setCloudClientEnabled,
  setDefaultCloudClient,
  deleteCloudClient,
  reorderCloudClients,
  setCloudClientAllowSelect,
  createCloudClient,
  updateCloudClient,
  refreshCloudClient,
  refreshAllCloudClients,
  updateCloudClientKey,
  pushCloudClientUpdate
} from '@/api/erp'
import Sortable from 'sortablejs'

const emptyEditForm = () => ({
  id: '',
  name: '',
  path: '',
  mcVersion: '',
  enabled: true,
  aliasesText: '',
  pool: ''
})

export default {
  name: 'WebGameCloudClients',
  data() {
    return {
      loading: false,
      rows: [],
      defaultId: '',
      allowPlayerSelect: true,
      allowSelectSaving: false,
      refreshingAll: false,
      refreshingId: '',
      pushingId: '',
      sortable: null,
      editVisible: false,
      editEditing: false,
      editSaving: false,
      editForm: emptyEditForm()
    }
  },
  computed: {
    entryUrl() {
      return window.location.origin + '/web/plugins/WebGame/cloud/'
    }
  },  created() {
    this.getList()
  },
  beforeDestroy() {
    this.destroySortable()
  },
  methods: {
    getList() {
      this.loading = true
      listCloudClients().then(res => {
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
      reorderCloudClients(ids).then(res => {
        this.$modal.msgSuccess(res.msg || '排序已保存')
      }).catch(() => {
      }).finally(() => {
        this.getList()
      })
    },
    onAllowSelectChange(val) {
      const apply = () => {
        this.allowSelectSaving = true
        setCloudClientAllowSelect(val).then(res => {
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
        '关闭后玩家访问 /cloud/ 将直接进入默认客户端，其它客户端页面不可访问。是否继续？',
        '禁止玩家自选客户端',
        { type: 'warning', confirmButtonText: '禁止自选', cancelButtonText: '取消' }
      ).then(apply).catch(() => {
        this.allowPlayerSelect = true
      })
    },
    openCreate() {
      this.editEditing = false
      this.editForm = emptyEditForm()
      this.editVisible = true
    },
    openEdit(row) {
      this.editEditing = true
      this.editForm = {
        id: row.id,
        name: row.name,
        path: row.path,
        mcVersion: row.mcVersion,
        enabled: row.enabled,
        aliasesText: (row.aliases || []).join(', '),
        pool: row.pool || ''
      }
      this.editVisible = true
    },
    async submitEdit() {
      if (!this.editForm.path) {
        this.$message.error('请填写客户端路径')
        return
      }
      if (this.editForm.path.endsWith('/') || this.editForm.path.endsWith('\\')) {
        this.$message.error('请填写具体的入口文件路径，例如 index.html')
        return
      }
      const payload = {
        name: this.editForm.name,
        path: this.editForm.path,
        mcVersion: this.editForm.mcVersion,
        enabled: this.editForm.enabled,
        aliases: (this.editForm.aliasesText || '')
          .split(/[,，\s]+/)
          .map(s => s.trim())
          .filter(s => s.length > 0),
        pool: (this.editForm.pool || '').trim()
      }
      this.editSaving = true
      try {
        const res = this.editEditing
          ? await updateCloudClient(this.editForm.id, payload)
          : await createCloudClient(payload)
        this.$modal.msgSuccess(res.msg || '已保存')
        this.editVisible = false
        this.getList()
      } finally {
        this.editSaving = false
      }
    },
    sourceLabel(source) {
      return { url: 'URL', local: '本地' }[source] || source || '—'
    },
    sourceTagType(source) {
      return { url: 'warning', local: 'success' }[source] || ''
    },
    runtimeLabel(status) {
      return {
        online: '在线',
        offline: '离线',
        degraded: '健康异常',
        unauthorized: '密钥不符',
        unknown: '未探测'
      }[status] || '未探测'
    },
    runtimeTagType(status) {
      return {
        online: 'success',
        offline: 'danger',
        degraded: 'warning',
        unauthorized: 'warning',
        unknown: 'info'
      }[status] || 'info'
    },
    /** 别名对应的根路径短地址（服务端已把别名规范化成小写 ASCII / 中文原样）。 */
    rootEntry(alias) {
      return window.location.origin + '/cloud/' + alias
    },
    handlePushUpdate(row) {
      this.$confirm(
        `把 config.yml 里的期望版本推送给 ${row.id}（${row.name}）？` +
          '机器会后台下载并解包，期间该机器不可起新会话。',
        '下发客户端更新',
        { type: 'warning', confirmButtonText: '下发', cancelButtonText: '取消' }
      ).then(() => {
        this.pushingId = String(row.id)
        pushCloudClientUpdate(row.id).then(res => {
          this.$modal.msgSuccess(res.msg || '已开始下发')
          setTimeout(this.getList, 3000)
        }).catch(() => {}).finally(() => {
          this.pushingId = ''
        })
      }).catch(() => {})
    },
    handleRefresh(row) {
      if (!row.address) {
        this.$message.error('该条目未配置设备地址（ip:port）')
        return
      }
      this.refreshingId = String(row.id)
      refreshCloudClient(row.id).then(res => {
        this.$modal.msgSuccess(res.msg || '刷新完成')
        this.getList()
      }).catch(() => {
      }).finally(() => {
        this.refreshingId = ''
      })
    },
    handleRefreshAll() {
      this.refreshingAll = true
      refreshAllCloudClients().then(res => {
        this.$modal.msgSuccess(res.msg || '全部刷新完成')
        this.getList()
      }).catch(() => {
      }).finally(() => {
        this.refreshingAll = false
      })
    },
    handleUpdateKey(row) {
      const target = row.address ? `${row.id}（${row.address}）` : `${row.id}（未上报地址）`
      this.$prompt(
        `为设备 ${target} 输入共享密钥（X-Cloud-Key，即 stream-setting 中的“访问口令”）`,
        '更新共享密钥',
        {
          confirmButtonText: '验证并更新',
          cancelButtonText: '取消',
          inputPlaceholder: '粘贴访问口令',
          inputValidator: value => (value && value.trim() ? true : '密钥不能为空')
        }
      ).then(({ value }) => {
        updateCloudClientKey(row.id, value.trim()).then(res => {
          this.$modal.msgSuccess(res.msg || '密钥已更新')
          this.getList()
        })
      }).catch(() => {})
    },
    onToggle(row, val) {
      setCloudClientEnabled(row.id, val).then(res => {
        this.$modal.msgSuccess(res.msg || '操作成功')
        this.getList()
      }).catch(() => {
        row.enabled = !val
      })
    },
    handleSetDefault(row) {
      setDefaultCloudClient(row.id).then(res => {
        this.$modal.msgSuccess(res.msg || '已设为默认')
        this.getList()
      })
    },
    handleDelete(row) {
      this.$confirm(
        `确认删除云游戏客户端 ${row.id}（${row.name}）？`,
        '删除确认',
        { type: 'warning' }
      ).then(() => {
        deleteCloudClient(row.id).then(res => {
          this.$modal.msgSuccess(res.msg || '已删除')
          this.getList()
        })
      }).catch(() => {})
    },
    openPage(row) {
      window.open(row.pagePath, '_blank')
    },
    /** 玩家入口完整链接：跳转页由服务端补 token，这里不需要暴露密钥。 */
    liveUrl(row) {
      return window.location.origin + (row.pagePath || '')
    },
    copyText(text) {
      if (!text) {
        return
      }
      const fallback = () => {
        const input = document.createElement('textarea')
        input.value = text
        input.style.position = 'fixed'
        input.style.opacity = '0'
        document.body.appendChild(input)
        input.select()
        try {
          document.execCommand('copy')
          this.$modal.msgSuccess('链接已复制')
        } catch (e) {
          this.$message.error('复制失败，请手动复制：' + text)
        }
        document.body.removeChild(input)
      }
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(() => {
          this.$modal.msgSuccess('链接已复制')
        }).catch(fallback)
        return
      }
      fallback()
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
.runtime-hint {
  margin-top: 2px;
  font-size: 11px;
  color: #909399;
}
.live-path {
  display: inline-block;
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
  margin: 0 4px;
  font-size: 12px;
  color: #909399;
}
.form-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.reason {
  display: inline-block;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
  font-size: 12px;
  color: #e6a23c;
}
.alias-link {
  display: inline-block;
  margin: 0 6px 2px 0;
  font-size: 12px;
  color: #409eff;
  text-decoration: none;
}
.alias-link:hover {
  text-decoration: underline;
}
.ver-line {
  font-size: 12px;
  color: #606266;
  line-height: 20px;
  text-align: left;
}
</style>
