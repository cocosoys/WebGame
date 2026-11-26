<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-refresh" size="mini" :loading="loading" @click="getList">刷新</el-button>
      </el-col>
      <el-col :span="6">
        <el-radio-group v-model="clientType" size="mini">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button label="eagler">eagler</el-radio-button>
          <el-radio-button label="cloud">云游戏</el-radio-button>
        </el-radio-group>
      </el-col>
      <el-col :span="4">
        <span style="font-size: 12px; color: #909399; line-height: 28px">共 {{ filteredSessions.length }} 个会话，每 5 秒自动刷新</span>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="filteredSessions" size="small">
      <el-table-column label="用户名" align="center" min-width="140">
        <template slot-scope="scope">
          <span v-if="scope.row.username">{{ scope.row.username }}</span>
          <span v-else style="color: #909399">（握手未完成）</span>
        </template>
      </el-table-column>
      <el-table-column label="IP" align="center" prop="ip" width="140" />
      <el-table-column label="客户端" align="center" width="160">
        <template slot-scope="scope">
          <el-tag
            v-if="scope.row.clientId"
            size="mini"
            :type="scope.row.clientType === 'cloud' ? 'success' : 'warning'"
            :title="scope.row.clientId"
          >{{ scope.row.clientName || scope.row.clientId }}</el-tag>
          <span v-else style="color: #909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" align="center" width="100">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.playing" type="success" size="mini">游戏中</el-tag>
          <el-tag v-else-if="scope.row.clientType === 'cloud'" type="warning" size="mini">启动中</el-tag>
          <el-tag v-else type="info" size="mini">握手中</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="阶段" align="center" width="110">
        <template slot-scope="scope">
          <span v-if="scope.row.clientType === 'cloud'">
            {{ stageText(scope.row.stage, scope.row.playing) }}
          </span>
          <span v-else style="color: #909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="连接时间" align="center" width="170">
        <template slot-scope="scope">
          <span v-if="scope.row.connectedAt">{{ parseTime(scope.row.connectedAt) }}</span>
          <span v-else style="color: #909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="在线时长" align="center" width="120">
        <template slot-scope="scope">{{ formatDuration(scope.row.durationSeconds) }}</template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="110" fixed="right">
        <template slot-scope="scope">
          <el-button type="text" size="mini" style="color: #f56c6c" @click="handleKick(scope.row)">
            强制踢出
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import { listSessions, kickSession } from '@/api/erp'

export default {
  name: 'WebGameSession',
  data() {
    return {
      loading: false,
      timer: null,
      clientType: '',
      sessionList: []
    }
  },
  computed: {
    filteredSessions() {
      if (!this.clientType) {
        return this.sessionList
      }
      return this.sessionList.filter(r => r.clientType === this.clientType)
    }
  },
  created() {
    this.getList()
    this.timer = setInterval(this.getList, 5000)
  },
  beforeDestroy() {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
  },
  methods: {
    getList() {
      this.loading = true
      listSessions().then(res => {
        this.sessionList = res.rows || []
      }).finally(() => {
        this.loading = false
      })
    },
    formatDuration(seconds) {
      const s = seconds || 0
      if (s < 60) return s + ' 秒'
      const m = Math.floor(s / 60)
      if (m < 60) return m + ' 分 ' + (s % 60) + ' 秒'
      const h = Math.floor(m / 60)
      return h + ' 小时 ' + (m % 60) + ' 分'
    },
    stageText(stage, playing) {
      if (playing) return '画面就绪'
      const map = { workspace: '分配工作区', jvm: '起 JVM', join: '等待进服', screen: '画面就绪' }
      return map[stage] || (stage || '—')
    },
    handleKick(row) {
      const who = row.username || row.sessionId
      const tip = row.clientType === 'cloud'
        ? '该操作会结束云游戏会话：关闭直播通道、让云机收尾并释放槽位。'
        : '该操作会立即断开其游戏连接。'
      this.$confirm(
        `确认将 ${who} 强制踢出？${tip}`,
        '强制踢出',
        { type: 'warning', confirmButtonText: '踢出', cancelButtonText: '取消' }
      ).then(() => {
        kickSession(row.sessionId).then(res => {
          this.$modal.msgSuccess(res.msg || '已踢出')
          this.getList()
        })
      }).catch(() => {})
    }
  }
}
</script>
