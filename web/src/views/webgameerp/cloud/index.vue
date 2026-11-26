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
      <el-col :span="24" style="margin-top: 8px">
        <span class="entry-hint">管理范围：</span>
        <el-select
          v-model="scope"
          size="mini"
          style="width: 180px"
          @change="onScopeChange"
        >
          <el-option label="全部" value="all" />
          <el-option
            v-for="s in scopeOptions"
            :key="s.value"
            :label="s.label + '（' + s.count + '）'"
            :value="s.value"
          />
        </el-select>
        <span class="entry-hint" style="margin-left: 8px">
          scope 只用于过滤列表，不代表权限边界；换机请先删除旧记录，等云机重新上报生成新 ID。
        </span>
      </el-col>
    </el-row>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="云游戏设备的“期望客户端包”只在「更多操作 → 下发更新」时显式选择并回写；客户端管理里的“下发预选”只做弹窗默认值，不会自动静默下发。"
      style="margin-bottom: 12px"
    />

    <el-table ref="table" v-loading="loading" :data="rows" size="small">
      <el-table-column label="排序" align="center" width="60">
        <template slot-scope="scope">
          <i class="el-icon-rank drag-handle" title="拖拽调整顺序" />
        </template>
      </el-table-column>
      <el-table-column label="名称" align="center" prop="name" min-width="150" show-overflow-tooltip />
      <el-table-column label="管理范围" align="center" width="120">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.adminScope" size="mini" type="success">{{ scope.row.adminScope }}</el-tag>
          <el-tag v-else size="mini" type="info">未分配</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="运行状态" align="center" width="130">
        <template slot-scope="scope">
          <el-tag size="mini" :type="runtimeTagType(scope.row.status)">{{ runtimeLabel(scope.row.status) }}</el-tag>
          <el-tag v-if="scope.row.busy" size="mini" type="warning" style="margin-left: 4px">占用</el-tag>
          <el-tag v-if="scope.row.endUnconfirmed" size="mini" type="danger" style="margin-left: 4px">结束未确认</el-tag>
          <div class="runtime-hint">
            <span v-if="scope.row.latencyMs">延迟 {{ scope.row.latencyMs }}ms</span>
            <span v-else-if="scope.row.version">v{{ scope.row.version }}</span>
            <span v-else>—</span>
          </div>
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
      <el-table-column label="MC 版本" align="center" min-width="120">
        <template slot-scope="scope">
          <span v-if="scope.row.mcVersion">{{ scope.row.mcVersion }}</span>
          <span v-else style="color: #909399">未填写</span>
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
      <el-table-column label="操作" align="center" width="320">
        <template slot-scope="scope">
          <el-button
            type="text"
            size="mini"
            :loading="refreshingId === scope.row.id"
            :disabled="!scope.row.address"
            @click="handleRefresh(scope.row)"
          >刷新</el-button>
          <el-button type="text" size="mini" @click="openEdit(scope.row)">编辑</el-button>
          <el-button
            type="text"
            size="mini"
            style="color: #f56c6c"
            :disabled="scope.row.busy || scope.row.endUnconfirmed"
            @click="handleDelete(scope.row)"
          >删除</el-button>
          <el-popover
            placement="bottom-end"
            width="160"
            trigger="click"
            :append-to-body="true"
            popper-class="cloud-more-popover"
          >
            <div class="more-actions">
              <el-button
                type="text"
                size="mini"
                :disabled="scope.row.source !== 'url' || !scope.row.address || scope.row.busy"
                @click="handleMore('push', scope.row)"
              >下发更新</el-button>
              <el-button
                type="text"
                size="mini"
                :disabled="scope.row.defaultVersion || !scope.row.enabled || scope.row.missing"
                @click="handleMore('default', scope.row)"
              >设为默认设备</el-button>
              <el-button type="text" size="mini" @click="handleMore('key', scope.row)">密钥</el-button>
              <el-button
                type="text"
                size="mini"
                :disabled="!scope.row.busy && !scope.row.endUnconfirmed"
                @click="handleMore('forceEnd', scope.row)"
              >强制结束</el-button>
              <el-button
                type="text"
                size="mini"
                :disabled="scope.row.busy || scope.row.endUnconfirmed || !scope.row.address"
                @click="handleMore('restart', scope.row)"
              >重启游戏</el-button>
              <el-button type="text" size="mini" @click="handleMore('openPage', scope.row)">打开页面</el-button>
              <el-button type="text" size="mini" @click="handleMore('copyEntry', scope.row)">复制入口</el-button>
            </div>
            <el-button slot="reference" type="text" size="mini">更多操作<i class="el-icon-arrow-down el-icon--right" /></el-button>
          </el-popover>
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
        <el-form-item label="名称">
          <el-input v-model="editForm.name" maxlength="64" placeholder="展示名称" />
        </el-form-item>
        <el-form-item label="URL" required>
          <el-input
            v-model="editForm.path"
            placeholder="https://192.168.59.128:8080/（基础地址，可带结尾 /）"
          />
          <div class="form-hint">
            URL 客户端填云机基础地址即可，结尾是否带 / 都可以；本地页面才必须写到具体入口文件（如 index.html）。
          </div>
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
        <el-form-item label="管理范围">
          <el-input v-model="editForm.adminScope" maxlength="24" placeholder="留空 = 未分配；仅用于 ERP 列表过滤" />
          <div class="form-hint">
            纯过滤字段，不是权限边界；ROOT 等管理员可切换任意范围查看。
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

    <el-dialog
      title="下发客户端更新"
      :visible.sync="pushVisible"
      width="620px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="下发会覆盖目标机器当前客户端目录；机器后台下载、解包期间不能起新会话。请确认版本和解包目录。"
        style="margin-bottom: 12px"
      />
      <el-form label-width="100px" size="small">
        <el-form-item label="目标设备">
          <span>{{ pushTarget.name }}（{{ pushTarget.id }}）</span>
        </el-form-item>
        <el-form-item label="客户端包" required>
          <el-select
            v-model="pushPackageId"
            filterable
            placeholder="选择一个启用的云游戏客户端包"
            style="width: 100%"
          >
            <el-option
              v-for="pkg in cloudPackages"
              :key="pkg.id"
              :label="pushPackageLabel(pkg)"
              :value="pkg.id"
              :disabled="!pkg.enabled"
            />
          </el-select>
          <div v-if="cloudPackages.length === 0" class="form-hint">
            没有可用的云游戏包，请先到「客户端管理」上传。
            <router-link to="/webgameerp/client-center/packages">去客户端管理</router-link>
          </div>
        </el-form-item>
        <el-form-item v-if="pushPackage" label="确认信息">
          <div class="push-detail">
            <div>版本：{{ pushPackage.version || '未填写' }}</div>
            <div>解包目录：{{ pushPackage.targetDir || pushPackage.displayPath || '—' }}</div>
            <div>文件大小：{{ formatSize(pushPackage.size) }}</div>
            <div>SHA-256：{{ pushPackage.sha256 || '—' }}</div>
          </div>
          <el-alert
            v-if="pushTarget.packageId && pushTarget.packageId !== pushPackageId"
            type="warning"
            :closable="false"
            show-icon
            title="该设备当前期望包与本次选择不同，下发成功后会把设备期望包改写为本次选择的包。"
            style="margin-top: 8px"
          />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button size="small" :disabled="pushingId !== ''" @click="pushVisible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="pushingId !== ''" @click="confirmPush">确认下发</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  listCloudClients,
  listCloudClientScopes,
  setCloudClientEnabled,
  setDefaultCloudClient,
  deleteCloudClient,
  forceEndCloudClientSession,
  restartCloudClientGame,
  reorderCloudClients,
  setCloudClientAllowSelect,
  createCloudClient,
  updateCloudClient,
  refreshCloudClient,
  refreshAllCloudClients,
  updateCloudClientKey,
  pushCloudClientUpdate,
  listClientPackages
} from '@/api/erp'
import Sortable from 'sortablejs'

const emptyEditForm = () => ({
  id: '',
  name: '',
  path: '',
  enabled: true,
  aliasesText: '',
  pool: '',
  adminScope: ''
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
      scope: 'all',
      scopes: [],
      sortable: null,
      editVisible: false,
      editEditing: false,
      editSaving: false,
      editForm: emptyEditForm(),
      cloudPackages: [],
      pushVisible: false,
      pushTarget: {},
      pushPackageId: ''
    }
  },
  computed: {
    entryUrl() {
      return window.location.origin + '/web/plugins/WebGame/cloud/'
    },
    scopeOptions() {
      const list = this.scopes.slice()
      const cur = this.scope
      if (cur && cur !== 'all' && !list.some(s => s.value === cur)) {
        list.unshift({
          value: cur,
          label: cur === '__unassigned__' ? '未分配' : cur,
          count: 0
        })
      }
      return list
    },
    pushPackage() {
      return this.cloudPackages.find(p => p.id === this.pushPackageId) || null
    }
  },  created() {
    const q = this.$route && this.$route.query ? this.$route.query.scope : ''
    this.scope = typeof q === 'string' && q ? q : 'all'
    this.getList()
    this.loadPackages()
  },
  beforeDestroy() {
    this.destroySortable()
  },
  methods: {
    getList() {
      this.loading = true
      this.loadScopes()
      listCloudClients(this.scope).then(res => {
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
    loadScopes() {
      listCloudClientScopes().then(res => {
        this.scopes = (res.data && res.data.scopes) || []
      }).catch(() => {})
    },
    loadPackages() {
      listClientPackages('cloud').then(res => {
        const data = res.data || {}
        this.cloudPackages = data.rows || data.packages || []
      }).catch(() => {})
    },
    formatSize(n) {
      if (!n) return '0 B'
      if (n >= 1024 * 1024 * 1024) return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB'
      if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
      if (n >= 1024) return (n / 1024).toFixed(1) + ' KB'
      return n + ' B'
    },
    pushPackageLabel(pkg) {
      const version = pkg.version ? ` · ${pkg.version}` : ''
      return `${pkg.name}${version}${pkg.enabled ? '' : '（已停用）'}`
    },
    onScopeChange(val) {
      const query = Object.assign({}, this.$route.query)
      if (val && val !== 'all') {
        query.scope = val
      } else {
        delete query.scope
      }
      this.$router.replace({ path: this.$route.path, query })
      this.getList()
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
        enabled: row.enabled,
        aliasesText: (row.aliases || []).join(', '),
        pool: row.pool || '',
        adminScope: row.adminScope || ''
      }
      this.editVisible = true
    },
    async submitEdit() {
      const path = (this.editForm.path || '').trim()
      if (!path) {
        this.$message.error('请填写客户端路径')
        return
      }
      // URL 是云机基础地址，结尾带 / 是合法写法；只有本地页面路径才要求写到具体文件。
      const isUrl = /^https?:\/\//i.test(path)
      if (!isUrl && (path.endsWith('/') || path.endsWith('\\'))) {
        this.$message.error('本地页面请填写具体的入口文件路径，例如 index.html')
        return
      }
      const payload = {
        name: this.editForm.name,
        path,
        enabled: this.editForm.enabled,
        aliases: (this.editForm.aliasesText || '')
          .split(/[,，\s]+/)
          .map(s => s.trim())
          .filter(s => s.length > 0),
        pool: (this.editForm.pool || '').trim(),
        adminScope: (this.editForm.adminScope || '').trim()
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
    handleMore(command, row) {
      switch (command) {
        case 'push': this.openPush(row); break
        case 'default': this.handleSetDefault(row); break
        case 'key': this.handleUpdateKey(row); break
        case 'forceEnd': this.handleForceEnd(row); break
        case 'restart': this.handleRestart(row); break
        case 'openPage': this.openPage(row); break
        case 'copyEntry': this.copyText(liveUrl(row)); break
        default: break
      }
    },
    openPush(row) {
      this.pushTarget = row
      const enabled = this.cloudPackages.filter(p => p.enabled)
      const preferred = enabled.find(p => p.id === row.packageId)
      const preselect = enabled.find(p => p.preselect)
      this.pushPackageId = (preferred || preselect || enabled[0] || {}).id || ''
      this.pushVisible = true
      if (this.cloudPackages.length === 0) this.loadPackages()
    },
    confirmPush() {
      if (!this.pushPackageId) {
        this.$message.warning('请选择要下发的客户端包')
        return
      }
      if (!this.pushPackage) {
        this.$message.warning('选择的客户端包不存在或已停用')
        return
      }
      this.pushingId = String(this.pushTarget.id)
      pushCloudClientUpdate(this.pushTarget.id, this.pushPackageId).then(res => {
        this.$modal.msgSuccess(res.msg || '已开始下发')
        this.pushVisible = false
        setTimeout(this.getList, 3000)
      }).catch(() => {}).finally(() => {
        this.pushingId = ''
      })
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
      refreshAllCloudClients(this.scope).then(res => {
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
    handleForceEnd(row) {
      this.$prompt(
        `强制结束 ${row.id}（${row.name}）当前的全部会话，并让云机停止游戏。原因可留空：`,
        '强制结束会话',
        {
          confirmButtonText: '强制结束',
          cancelButtonText: '取消',
          inputPlaceholder: '可留空',
          inputType: 'textarea'
        }
      ).then(({ value }) => {
        forceEndCloudClientSession(row.id, (value || '').trim()).then(res => {
          const data = res.data || {}
          if (data.partial) {
            this.$message.warning(res.msg || '远端未确认，删除/重启已被锁定')
          } else {
            this.$modal.msgSuccess(res.msg || '已强制结束')
          }
          this.getList()
        })
      }).catch(() => {})
    },
    handleRestart(row) {
      this.$confirm(
        `确认重启 ${row.id}（${row.name}）上的游戏？` +
          '该机器必须处于空闲且没有“结束未确认”标记。',
        '重启游戏',
        { type: 'warning', confirmButtonText: '重启', cancelButtonText: '取消' }
      ).then(() => {
        restartCloudClientGame(row.id).then(res => {
          this.$modal.msgSuccess(res.msg || '重启指令已发送')
          setTimeout(this.getList, 3000)
        })
      }).catch(() => {})
    },
    handleDelete(row) {
      if (row.busy || row.endUnconfirmed) {
        this.$message.error('云机占用中或上一轮结束未确认，不能删除')
        return
      }
      this.$prompt(
        `删除 ${row.id}（${row.name}）后，该 ID 不会复用；云机重新上报会生成新 ID 并进入“未分配”。` +
          '请输入删除原因（可留空）：',
        '删除确认',
        {
          confirmButtonText: '删除',
          cancelButtonText: '取消',
          inputPlaceholder: '可留空',
          inputType: 'textarea'
        }
      ).then(({ value }) => {
        return this.$confirm(
          '确认删除旧记录？旧 ID 的页面/别名/直播前缀会一并清理。',
          '再次确认',
          { type: 'warning', confirmButtonText: '删除旧记录', cancelButtonText: '取消' }
        ).then(() => deleteCloudClient(row.id, (value || '').trim()))
      }).then(res => {
        this.$modal.msgSuccess(res.msg || '已删除')
        this.getList()
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
.push-detail {
  padding: 8px 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafafa;
  font-size: 12px;
  line-height: 20px;
  color: #606266;
  word-break: break-all;
}
.more-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.more-actions .el-button {
  margin: 0;
  padding: 4px 0;
}
</style>
