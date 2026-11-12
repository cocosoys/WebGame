<template>
  <config-layout title="核心配置" file="config.yml" :help-map="helpMap" :dirty="dirty"
                 :loading="loading" :saving="saving" @reload="reload" @save="save">

    <!-- ready 守卫：首次渲染 model 尚为空、或个别节点缺失时，先不渲染深链表单，避免 TypeError 白屏 -->
    <div v-if="ready">

      <!-- ============ 页面托管 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">页面托管 (page)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="游戏页目录">
            <el-input v-model="model.page.path" placeholder="/eagler/" style="width: 320px" />
            <div class="hint">相对 /web/plugins/WebGame/，以 / 开头并以 / 结尾；页面文件为 dist/eagler/index.html。</div>
          </el-form-item>
          <el-form-item label="对外地址">
            <el-input v-model="model.page['public-host']" placeholder="127.0.0.1" style="width: 320px" />
            <div class="hint">玩家浏览器访问使用的主机名/IP；页面内 WS 地址取浏览器当前 host，此值仅用于展示与日志。</div>
          </el-form-item>
          <el-form-item label="对外端口">
            <el-input-number v-model="model.page['public-port']" :min="0" :max="65535" controls-position="right" />
            <div class="hint">0 = 跟随 server.properties 的 server-port。</div>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- ============ WS 隧道 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">WS 隧道 (tunnel)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="WS 路径">
            <el-input v-model="model.tunnel['ws-path']" placeholder="/eagler" style="width: 320px" />
            <div class="hint">必须与静态游戏页内写死的 /eagler 一致，修改后需同步重新构建前端资源。</div>
          </el-form-item>
          <el-form-item label="回环目标地址">
            <el-input v-model="model.tunnel['loopback-host']" placeholder="127.0.0.1" style="width: 320px" />
            <div class="hint">隧道转发的 MC 服务器地址（同机回环即 127.0.0.1）。</div>
          </el-form-item>
          <el-form-item label="回环端口">
            <el-input-number v-model="model.tunnel['loopback-port']" :min="0" :max="65535" controls-position="right" />
            <div class="hint">0 = 跟随 server-port（回环 MC 客户端连本服自身端口）。</div>
          </el-form-item>
          <el-form-item label="协议版本">
            <el-checkbox v-model="model.tunnel['allow-v3']">支持 EaglerX V3</el-checkbox>
            <el-checkbox v-model="model.tunnel['allow-v4']">支持 EaglerX V4</el-checkbox>
            <div class="hint">EaglerX 握手协议版本支持范围（V3=协议 2/3，V4=协议 4）。</div>
          </el-form-item>
          <el-form-item label="MC 协议范围">
            <el-input-number v-model="model.tunnel['min-mc-protocol']" :min="1" :max="999" controls-position="right" />
            <span style="margin: 0 8px">~</span>
            <el-input-number v-model="model.tunnel['max-mc-protocol']" :min="1" :max="999" controls-position="right" />
            <div class="hint">服务端 MC 协议版本范围，1.12.2 = 340。</div>
          </el-form-item>
          <el-form-item label="服务器品牌">
            <el-input v-model="model.tunnel['server-brand']" placeholder="WebGame" style="width: 320px" />
            <div class="hint">SERVER_VERSION 帧中的服务器品牌，供客户端显示。</div>
          </el-form-item>
          <el-form-item label="服务器版本">
            <el-input v-model="model.tunnel['server-version']" placeholder="1.0.0" style="width: 320px" />
            <div class="hint">SERVER_VERSION 帧中的服务器版本，供客户端显示。</div>
          </el-form-item>
          <el-form-item label="每设备并发上限">
            <el-input-number v-model="model.tunnel['max-connections-per-device']" :min="1" :max="64" controls-position="right" />
            <div class="hint">同一来源 IP 最多同时进入游戏的浏览器会话数，防止单设备占满名额。</div>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- ============ 自动进服 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">自动进服 (auto-join)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="启用自动进服">
            <el-switch v-model="model['auto-join'].enabled" />
            <div class="hint">打开页面后自动设置用户名并连接服务器，无需手动点击。</div>
          </el-form-item>
          <el-form-item label="默认用户名">
            <el-input v-model="model['auto-join']['default-username']" placeholder="WebPlayer" style="width: 320px" />
            <div class="hint">可用 URL 参数覆盖：…/web/plugins/WebGame/eagler/?user=Tom。</div>
          </el-form-item>
        </el-form>
      </el-card>

    </div>
    <el-empty v-else description="配置加载中…" />

  </config-layout>
</template>

<script>
import configPage from './configPage'
import ConfigLayout from '@/views/webgameerp/components/ConfigLayout.vue'

export default {
  name: 'WebGameConfig',
  mixins: [configPage],
  components: { ConfigLayout },
  data() {
    return {
      ready: false,
      fileId: 'config',
      helpMap: {
        '游戏页目录': 'page.path：游戏页所在目录（相对 /web/plugins/WebGame/，以 / 开头并以 / 结尾），页面文件为 dist/eagler/index.html。',
        '对外地址': 'page.public-host：玩家浏览器访问使用的主机名/IP；页面内 WS 地址取浏览器当前 host，此值仅用于展示与日志。',
        '对外端口': 'page.public-port：对外端口，0 = 跟随 server.properties 的 server-port。',
        'WS 路径': 'tunnel.ws-path：WebSocket 隧道入口路径，必须与静态游戏页内写死的 /eagler 一致。',
        '回环目标地址': 'tunnel.loopback-host：隧道转发的 MC 服务器地址，同机即 127.0.0.1。',
        '回环端口': 'tunnel.loopback-port：MC 回环端口，0 = 跟随 server-port。',
        '协议版本': 'tunnel.allow-v3 / tunnel.allow-v4：EaglerX 握手协议版本支持范围（V3=协议 2/3，V4=协议 4）。',
        'MC 协议范围': 'tunnel.min-mc-protocol / tunnel.max-mc-protocol：服务端 MC 协议版本范围，1.12.2 = 340。',
        '服务器品牌': 'tunnel.server-brand：SERVER_VERSION 帧中的服务器品牌，供客户端显示。',
        '服务器版本': 'tunnel.server-version：SERVER_VERSION 帧中的服务器版本，供客户端显示。',
        '每设备并发上限': 'tunnel.max-connections-per-device：同一来源 IP 最多同时进入游戏的浏览器会话数。',
        '启用自动进服': 'auto-join.enabled：打开页面后自动设置用户名并连接服务器。',
        '默认用户名': 'auto-join.default-username：自动进服使用的默认用户名，可用 URL 参数 ?user=Tom 覆盖。'
      }
    }
  },
  watch: {
    // mixin 异步 load 完成后整体替换 model；此时补齐缺失的嵌套对象，再放行渲染
    model(d) {
      if (d && typeof d === 'object') {
        this.ensureSkeleton(d)
        this.ready = true
      }
    }
  },
  methods: {
    // 只保证模板深链访问到的对象链存在，不臆造标量值；升级后缺节也能正常显示并可补录
    ensureSkeleton(d) {
      const ensure = (parent, key) => {
        if (!parent[key] || typeof parent[key] !== 'object') parent[key] = {}
        return parent[key]
      }
      ensure(d, 'page')
      ensure(d, 'tunnel')
      ensure(d, 'auto-join')
    }
  }
}
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.hint { font-size: 12px; color: #a8abb2; line-height: 1.5; }
</style>
