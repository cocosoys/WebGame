<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-refresh" size="mini" :loading="loading" @click="getStatus">刷新</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button icon="el-icon-monitor" size="mini" @click="openGame">打开游戏页</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button icon="el-icon-copy-document" size="mini" @click="copyPageUrl">复制页面地址</el-button>
      </el-col>
    </el-row>

    <el-row :gutter="12" v-loading="loading">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">插件状态</div>
          <div class="stat-value">
            <el-tag :type="status.enabled ? 'success' : 'danger'" size="small">{{ status.enabled ? '已启用' : '未启用' }}</el-tag>
          </div>
          <div class="stat-sub">v{{ status.pluginVersion || '-' }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">已运行</div>
          <div class="stat-value">{{ uptimeText }}</div>
          <div class="stat-sub">server-port {{ status.serverPort || '-' }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">在线玩家</div>
          <div class="stat-value">{{ status.onlinePlayers || 0 }}</div>
          <div class="stat-sub">Bukkit 在线人数</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">WS 会话</div>
          <div class="stat-value">{{ status.sessionCount || 0 }}</div>
          <div class="stat-sub">游戏中 {{ status.playingCount || 0 }} / 设备上限 {{ status.maxConnectionsPerDevice || '-' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 12px">
      <div slot="header" class="clearfix">
        <span>访问地址</span>
      </div>
      <el-form label-width="110px" size="small">
        <el-form-item label="游戏页面">
          <el-input :value="pageUrl" readonly>
            <el-button slot="append" icon="el-icon-document-copy" @click="copyText(pageUrl)"></el-button>
          </el-input>
        </el-form-item>
        <el-form-item label="WS 隧道">
          <el-input :value="status.wsUrl" readonly>
            <el-button slot="append" icon="el-icon-document-copy" @click="copyText(status.wsUrl)"></el-button>
          </el-input>
        </el-form-item>
        <el-form-item label="对外地址">
          <el-input :value="(status.publicHost || '-') + ':' + (status.publicPort || '-')" readonly />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import { getStatus } from '@/api/erp'
import soysContext from '@/soys/context'

export default {
  name: 'WebGameOverview',
  data() {
    return {
      loading: false,
      timer: null,
      status: {}
    }
  },
  computed: {
    pageUrl() {
      return soysContext.pageBase + 'eagler/'
    },
    uptimeText() {
      const s = this.status.uptimeSeconds || 0
      const d = Math.floor(s / 86400)
      const h = Math.floor((s % 86400) / 3600)
      const m = Math.floor((s % 3600) / 60)
      if (d > 0) return d + ' 天 ' + h + ' 小时'
      if (h > 0) return h + ' 小时 ' + m + ' 分'
      return m + ' 分 ' + (s % 60) + ' 秒'
    }
  },
  created() {
    this.getStatus()
    this.timer = setInterval(this.getStatus, 5000)
  },
  beforeDestroy() {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
  },
  methods: {
    getStatus() {
      this.loading = true
      getStatus().then(res => {
        this.status = res.data || {}
      }).finally(() => {
        this.loading = false
      })
    },
    openGame() {
      window.open(this.pageUrl, '_blank')
    },
    copyPageUrl() {
      this.copyText(window.location.origin + this.pageUrl)
    },
    copyText(text) {
      if (!text) return
      const input = document.createElement('textarea')
      input.value = text
      document.body.appendChild(input)
      input.select()
      try {
        document.execCommand('copy')
        this.$message.success('已复制')
      } catch (e) {
        this.$message.warning('复制失败，请手动选择复制')
      }
      document.body.removeChild(input)
    }
  }
}
</script>

<style scoped>
.stat-card {
  margin-bottom: 12px;
}
.stat-label {
  color: #909399;
  font-size: 13px;
}
.stat-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 36px;
  color: #303133;
}
.stat-sub {
  color: #909399;
  font-size: 12px;
}
</style>
