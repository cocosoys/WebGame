<template>
  <div class="login">
    <el-form ref="loginForm" :model="loginForm" class="login-form">
      <h3 class="title">MCERP 后台管理系统</h3>
      <p class="soys-tip">登录由 SOYS 主插件提供：输入游戏内玩家名与登录密码，令牌自动跨页面共享。</p>
      <el-button type="primary" size="medium" class="login-button" @click="handleLogin">
        {{ loginForm.username ? '重新登录' : '点击登录' }}
      </el-button>
      <p v-if="loginState" class="soys-state">{{ loginState }}</p>
    </el-form>
  </div>
</template>

<script>
export default {
  name: "Login",
  data() {
    return {
      loginForm: {
        username: '',
        password: ''
      },
      loginState: ''
    }
  },
  created() {
    this.openSoysLogin()
  },
  methods: {
    // 打开 SOYS 主插件登录弹窗；成功后由全局 onLoginSuccess 统一跳转首页
    openSoysLogin() {
      if (typeof window === 'undefined' || !window.SoysAuth) {
        this.loginState = 'SOYS 登录组件未加载，请刷新页面重试'
        return
      }
      this.loginState = ''
      window.SoysAuth.openLogin().then(ok => {
        if (!ok) {
          this.loginState = '已取消登录，可点击"点击登录"重新打开登录窗口'
        }
        // ok=true 时由 main.js 全局 onLoginSuccess 处理跳转
      }).catch(e => {
        this.loginState = '登录窗口打开失败：' + (e && e.message ? e.message : e)
      })
    },
    handleLogin() {
      this.openSoysLogin()
    }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss">
$bg: #2d3a4b;
$dark_gray: #889aa4;
$light_gray: #eee;

.login {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  background-color: $bg;
}

.login-form {
  border-radius: 6px;
  background: #ffffff;
  width: 385px;
  padding: 25px 25px 5px 25px;

  .el-input {
    height: 38px;

    input {
      height: 38px;
    }
  }

  .input-icon {
    height: 39px;
    width: 14px;
    margin-left: 2px;
  }
}

.login-tip {
  font-size: 13px;
  text-align: center;
  color: #bfbfbf;
}

.title {
  margin: 0px auto 30px auto;
  text-align: center;
  color: #707070;
}

.soys-tip {
  font-size: 13px;
  color: #889aa4;
  text-align: center;
  margin: -10px 0 20px 0;
  line-height: 1.6;
}

.login-button {
  width: 100%;
  margin-bottom: 8px;
}

.soys-state {
  font-size: 12px;
  color: #e6a23c;
  text-align: center;
  margin: 6px 0 12px 0;
  min-height: 18px;
}
</style>
