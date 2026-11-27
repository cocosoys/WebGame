<template>
  <config-layout title="核心配置" file="config.yml" :help-map="helpMap" :dirty="dirty"
                 :loading="loading" :saving="saving" @reload="reload" @save="save">

    <!-- ready 守卫：首次渲染 model 尚为空、或个别节点缺失时，先不渲染深链表单，避免 TypeError 白屏 -->
    <div v-if="ready">

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="本页只保留管理员可维护的运行参数；页面路径、协议、默认项与内部编号不在 config.yml 中，默认项取排序最小的记录。"
        style="margin-bottom: 14px"
      />

      <!-- ============ WS 隧道 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">WS 隧道 (tunnel)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="服务器品牌">
            <el-input v-model="model.tunnel['server-brand']" placeholder="WebGame" style="width: 320px" />
            <div class="hint">SERVER_VERSION 帧中的服务器品牌，供客户端显示；服务器版本直接取插件自身版本。</div>
          </el-form-item>
          <el-form-item label="每设备并发上限">
            <el-input-number v-model="model.tunnel['max-connections-per-device']" :min="1" :max="64" controls-position="right" />
            <div class="hint">同一来源 IP 最多同时进入游戏的浏览器会话数，防止单设备占满名额。</div>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- ============ Eagler 入口 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">Eagler 入口 (eagler)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="允许玩家自选版本">
            <el-switch v-model="model.eagler['allow-player-select']" />
            <div class="hint">开启后玩家在 /eagler/ 选择页自行挑版本；关闭后直接进入排序最小的可用版本。</div>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- ============ 自动进服 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">自动进服 (auto-join)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="启用自动进服">
            <el-switch v-model="model['auto-join'].enabled" />
            <div class="hint">开启后：本机浏览器<b>记过游戏名</b>时，打开页面直接用它进服（不再弹框、不用再点）；
              没记过则弹框预填「默认用户名」。服务端与页面共用这一份开关（入口页读
              <code>/api/plugins/WebGame/cloud/page-config</code>，改完刷新生效）。</div>
          </el-form-item>
          <el-form-item label="默认用户名">
            <el-input v-model="model['auto-join']['default-username']" placeholder="WebPlayer" style="width: 320px" />
            <div class="hint">仅在<b>没有记住游戏名</b>时用来预填输入框；<b>不是</b>所有人共用的登录名——
              离线模式下同名会把彼此踢下线。也可用 URL 参数强制指定：…/web/plugins/WebGame/eagler/?user=Tom。</div>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- ============ 云设备健康探测 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">云设备健康探测 (cloud.probe)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="探测周期（秒）">
            <el-input-number v-model="model.cloud['heartbeat-seconds']" :min="3" :max="3600" controls-position="right" />
            <div class="hint">每轮请求 cloud-clients.yml 中登记设备的 GET /api/v1/health。</div>
          </el-form-item>
          <el-form-item label="离线阈值（秒）">
            <el-input-number v-model="model.cloud['ttl-seconds']" :min="1" :max="86400" controls-position="right" />
            <div class="hint">最近一次成功探测超过该时长才判离线，默认 3 个探测周期。</div>
          </el-form-item>
          <el-form-item label="探测超时（毫秒）">
            <el-input-number v-model="model.cloud['probe-timeout-ms']" :min="500" :max="15000" controls-position="right" />
            <div class="hint">单次探测的连接/读取超时，范围 500–15000 毫秒。</div>
          </el-form-item>
          <el-form-item label="跳过证书校验">
            <el-switch v-model="model.cloud.probe['trust-all']" />
            <div class="hint">true = 跳过证书链与主机名校验；仅用于无法导入证书的老内网机器，会放弃探测侧身份保证。</div>
          </el-form-item>
          <el-form-item label="独立信任库">
            <el-input v-model="model.cloud.probe['trust-store']" placeholder="trust-cloud.jks（留空 = JVM 默认信任库）" style="width: 320px" />
            <div class="hint">只用于 WebGame → cloud-stream 的探针/控制指令；相对路径按服务端根目录解析。</div>
          </el-form-item>
          <el-form-item label="信任库密码">
            <el-input v-model="model.cloud.probe['trust-store-password']" placeholder="changeit" show-password style="width: 320px" />
            <div class="hint">信任库文件 mtime/大小变化后下一轮自动重载，新增云机证书不必重启 MC。</div>
          </el-form-item>
          <el-form-item label="信任库类型">
            <el-input v-model="model.cloud.probe['trust-store-type']" placeholder="JKS" style="width: 200px" />
            <div class="hint">常见值：JKS / PKCS12；留空时按 JKS 处理。</div>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- ============ 云游戏页面与会话 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">云游戏页面与会话 (cloud.relay / heartbeat / progress)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="允许玩家自选设备">
            <el-switch v-model="model.cloud['allow-player-select']" />
            <div class="hint">开启后玩家在 /cloud/ 选择页自行挑设备；关闭后直接进入排序最小的可用设备。</div>
          </el-form-item>
          <el-form-item label="同端口转播">
            <el-switch v-model="model.cloud['same-port-relay']" />
            <div class="hint">开启后 URL 客户端的页面/资源/WebSocket 经 MC 端口转播，玩家不必直连云主机端口。</div>
          </el-form-item>
          <el-form-item label="会话代理端口">
            <el-input-number v-model="model.cloud['session-agent-port']" :min="1" :max="65535" controls-position="right" />
            <div class="hint">云主机上的 cloud-session-agent 监听端口，默认 8090；改端口要同步改代理与防火墙。</div>
          </el-form-item>
          <el-form-item label="启用浏览器心跳">
            <el-switch v-model="model.cloud.heartbeat.enabled" />
            <div class="hint">包装页按间隔上报 /cloud/session/heartbeat，超时判定玩家关闭页面并释放槽位。</div>
          </el-form-item>
          <el-form-item label="心跳间隔（秒）">
            <el-input-number v-model="model.cloud.heartbeat['interval-seconds']" :min="1" :max="600" controls-position="right" />
            <div class="hint">0 在模板中表示用页面默认值；后台标签页会被浏览器节流到约 1 次/分钟。</div>
          </el-form-item>
          <el-form-item label="心跳超时（秒）">
            <el-input-number v-model="model.cloud.heartbeat['timeout-seconds']" :min="1" :max="600" controls-position="right" />
            <div class="hint">低于 60 秒时，玩家把游戏页切到后台可能被误判离；建议 90–120。</div>
          </el-form-item>
          <el-form-item label="初始进度时长（秒）">
            <el-input-number v-model="model.cloud.progress['initial-seconds']" :min="1" :max="600" controls-position="right" />
            <div class="hint">无论成功与否，先把进度推到 initial-percent 的时长。</div>
          </el-form-item>
          <el-form-item label="初始进度百分比">
            <el-input-number v-model="model.cloud.progress['initial-percent']" :min="1" :max="99" controls-position="right" />
            <div class="hint">默认 90；到初始百分比后进入随机缓爬。</div>
          </el-form-item>
          <el-form-item label="缓爬间隔最小（秒）">
            <el-input-number v-model="model.cloud.progress['tick-min-seconds']" :min="1" :max="600" controls-position="right" />
            <span style="margin: 0 8px">~</span>
            <el-input-number v-model="model.cloud.progress['tick-max-seconds']" :min="1" :max="600" controls-position="right" />
            <div class="hint">90% 之后每次 +1 的间隔区间，随机取值。</div>
          </el-form-item>
          <el-form-item label="进度封顶百分比">
            <el-input-number v-model="model.cloud.progress['cap-percent']" :min="1" :max="100" controls-position="right" />
            <div class="hint">进度爬到该百分比后停住，等待真实进服信号揭开画面。</div>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- ============ 揭幕与选机 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">揭幕与选机 (cloud.reveal / pool)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="启动兜底（秒）">
            <el-input-number v-model="model.cloud.reveal['startup-timeout-seconds']" :min="1" :max="3600" controls-position="right" />
            <div class="hint">游戏进程起来后仍看不到云机进服，超时结束会话释放机器。</div>
          </el-form-item>
          <el-form-item label="进服窗口（秒）">
            <el-input-number v-model="model.cloud.reveal['join-window-seconds']" :min="1" :max="600" controls-position="right" />
            <div class="hint">玩家进服后，再给页面这么久上报「画面已载入」。</div>
          </el-form-item>
          <el-form-item label="揭幕下限（秒）">
            <el-input-number v-model="model.cloud.reveal['min-hold-seconds']" :min="0" :max="600" controls-position="right" />
            <div class="hint">进服后遮罩至少再压这么久，避免画面闪一下又黑屏。</div>
          </el-form-item>
          <el-form-item label="选机策略">
            <el-select v-model="model.cloud.pool.strategy" style="width: 240px">
              <el-option label="order（按 ERP 排序取第一台）" value="order" />
              <el-option label="latency（延迟最低优先）" value="latency" />
              <el-option label="recent（最久未使用优先）" value="recent" />
            </el-select>
            <div class="hint">前提永远是“在线 + 健康 + 空闲 + 口令一致”，策略只决定合格机器里挑哪台。</div>
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
        '服务器品牌': 'tunnel.server-brand：SERVER_VERSION 帧中的服务器品牌，供客户端显示；版本号取插件自身版本。',
        '每设备并发上限': 'tunnel.max-connections-per-device：同一来源 IP 最多同时进入游戏的浏览器会话数。',
        '允许玩家自选版本': 'eagler.allow-player-select：开启玩家在 /eagler/ 自选版本；关闭直接进入排序最小的可用版本。',
        '启用自动进服': 'auto-join.enabled：记住过游戏名的浏览器打开页面即自动进服；没记过则弹框预填默认用户名。',
        '默认用户名': 'auto-join.default-username：只在没有记住游戏名时预填输入框，不是公共登录名（离线模式同名互踢）。',
        '允许玩家自选设备': 'cloud.allow-player-select：开启玩家在 /cloud/ 自选设备；关闭直接进入排序最小的可用设备。',
        '探测周期（秒）': 'cloud.heartbeat-seconds：每轮请求登记设备 GET /api/v1/health 的周期。',
        '离线阈值（秒）': 'cloud.ttl-seconds：最近一次成功探测超过该时长才判离线，默认 3 个周期。',
        '探测超时（毫秒）': 'cloud.probe-timeout-ms：单次探测连接/读取超时，范围 500–15000。',
        '跳过证书校验': 'cloud.probe.trust-all：true = 跳过证书链与主机名校验，仅用于无法导入证书的老内网机器。',
        '独立信任库': 'cloud.probe.trust-store：只用于 WebGame → cloud-stream 探针/控制指令；相对路径按服务端根目录解析。',
        '信任库密码': 'cloud.probe.trust-store-password：独立信任库密码；文件变化后下一轮自动重载。',
        '信任库类型': 'cloud.probe.trust-store-type：JKS / PKCS12，默认 JKS。',
        '同端口转播': 'cloud.same-port-relay：URL 客户端页面/资源/WebSocket 经 MC 端口转播。',
        '会话代理端口': 'cloud.session-agent-port：cloud-session-agent 监听端口，默认 8090。',
        '启用浏览器心跳': 'cloud.heartbeat.enabled：包装页上报 /cloud/session/heartbeat，超时释放槽位。',
        '心跳间隔（秒）': 'cloud.heartbeat.interval-seconds：包装页心跳间隔；后台标签页会被浏览器节流。',
        '心跳超时（秒）': 'cloud.heartbeat.timeout-seconds：超过该时长收不到心跳即判玩家关闭页面；建议 90–120。',
        '初始进度时长（秒）': 'cloud.progress.initial-seconds：先把进度推到 initial-percent 的时长。',
        '初始进度百分比': 'cloud.progress.initial-percent：默认 90，之后进入随机缓爬。',
        '缓爬间隔最小（秒）': 'cloud.progress.tick-min-seconds / tick-max-seconds：90% 后每次 +1 的随机间隔区间。',
        '进度封顶百分比': 'cloud.progress.cap-percent：进度爬到该值后停住，等待真实进服信号。',
        '启动兜底（秒）': 'cloud.reveal.startup-timeout-seconds：游戏进程起来后仍无云机进服，超时结束会话。',
        '进服窗口（秒）': 'cloud.reveal.join-window-seconds：进服后页面还需上报“画面已载入”的等待时间。',
        '揭幕下限（秒）': 'cloud.reveal.min-hold-seconds：进服后遮罩至少再保留的时长。',
        '选机策略': 'cloud.pool.strategy：order / latency / recent，只决定合格机器中挑哪一台。'
      }
    }
  },
  watch: {
    // mixin 异步 load 完成后整体替换 model；此时补齐保留键，再放行渲染
    model(d) {
      if (d && typeof d === 'object') {
        this.ensureConfigDefaults(d)
        this.ready = true
      }
    }
  },
  methods: {
    /**
     * 补齐本页仍然保留的配置键。
     *
     * 只补 undefined/null，不覆盖已有值（false / 0 / 空字符串都是合法配置）；
     * 嵌套节缺失或类型不对时重建对象，保证表单深链访问永远有落脚点。
     */
    ensureConfigDefaults(d) {
      const ensure = (parent, key) => {
        const cur = parent[key]
        if (!cur || typeof cur !== 'object' || Array.isArray(cur)) {
          this.$set(parent, key, {})
        }
        return parent[key]
      }
      const setDefault = (parent, key, value) => {
        if (parent[key] === undefined || parent[key] === null) {
          this.$set(parent, key, value)
        }
      }

      const tunnel = ensure(d, 'tunnel')
      setDefault(tunnel, 'server-brand', 'WebGame')
      setDefault(tunnel, 'max-connections-per-device', 2)

      const eagler = ensure(d, 'eagler')
      setDefault(eagler, 'allow-player-select', true)

      const autoJoin = ensure(d, 'auto-join')
      setDefault(autoJoin, 'enabled', true)
      setDefault(autoJoin, 'default-username', 'WebPlayer')

      const cloud = ensure(d, 'cloud')
      setDefault(cloud, 'allow-player-select', true)
      setDefault(cloud, 'heartbeat-seconds', 90)
      setDefault(cloud, 'ttl-seconds', 270)
      setDefault(cloud, 'probe-timeout-ms', 3000)
      setDefault(cloud, 'same-port-relay', true)
      setDefault(cloud, 'session-agent-port', 8090)

      const probe = ensure(cloud, 'probe')
      setDefault(probe, 'trust-all', false)
      setDefault(probe, 'trust-store', '')
      setDefault(probe, 'trust-store-password', '')
      setDefault(probe, 'trust-store-type', 'JKS')

      const heartbeat = ensure(cloud, 'heartbeat')
      setDefault(heartbeat, 'enabled', true)
      setDefault(heartbeat, 'interval-seconds', 10)
      setDefault(heartbeat, 'timeout-seconds', 30)

      const progress = ensure(cloud, 'progress')
      setDefault(progress, 'initial-seconds', 15)
      setDefault(progress, 'initial-percent', 90)
      setDefault(progress, 'tick-min-seconds', 3)
      setDefault(progress, 'tick-max-seconds', 7)
      setDefault(progress, 'cap-percent', 99)

      const reveal = ensure(cloud, 'reveal')
      setDefault(reveal, 'startup-timeout-seconds', 180)
      setDefault(reveal, 'join-window-seconds', 30)
      setDefault(reveal, 'min-hold-seconds', 15)

      const pool = ensure(cloud, 'pool')
      setDefault(pool, 'strategy', 'order')
    }
  }
}
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.hint { font-size: 12px; color: #a8abb2; line-height: 1.5; }
</style>
