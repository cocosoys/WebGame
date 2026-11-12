<template>
  <div class="app-container home">
    <el-row :gutter="20">
      <el-col :sm="24" :lg="16" style="padding-left: 20px">
        <h2>SOYSHTTPOverMC ERP 管理台</h2>
        <p>
          欢迎回来，<b>{{ nickName || name }}</b>。这里是 SOYSHTTPOverMC 的 ERP 专属管理界面，
          提供用户权限、权限组与 X-API-KEY 的统一管理。
        </p>
      </el-col>
      <el-col :sm="24" :lg="8">
        <el-card shadow="hover" style="margin-top: 20px">
          <div slot="header"><b>快捷入口</b></div>
          <div style="line-height: 2.4">
            <el-button type="primary" size="mini" plain icon="el-icon-user" @click="go(moduleRoot + '/user')">用户列表</el-button>
            <el-button type="success" size="mini" plain icon="el-icon-key" @click="go(moduleRoot + '/group')">权限组列表</el-button>
            <el-button type="warning" size="mini" plain icon="el-icon-lock" @click="go(moduleRoot + '/apikey')">APIKEY 管理</el-button>
            <el-button type="info" size="mini" plain icon="el-icon-document" @click="go(moduleRoot + '/lang')">语言管理</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import soysContext from '@/soys/context'
export default {
  name: 'Index',
  data() {
    return { moduleRoot: soysContext.moduleRoot }
  },
  computed: {
    ...mapGetters(['name', 'nickName'])
  },
  methods: {
    go(path) {
      // wujie 模式：通知主应用跳；直接访问：子应用自己 router.push
      if (window.__POWERED_BY_WUJIE__ && window.$wujie && window.$wujie.bus) {
        window.$wujie.bus.$emit('router-push', path)
      } else {
        this.$router.push(path)
      }
    }
  }
}
</script>
