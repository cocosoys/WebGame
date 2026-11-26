<template>
  <config-layout title="核心配置" file="config.yml" :help-map="helpMap" :dirty="dirty"
                 :loading="loading" :saving="saving" @reload="reload" @save="save">

    <!-- ready 守卫：首次渲染 model 尚为空、或个别节点缺失时，先不渲染深链表单，避免 TypeError 白屏 -->
    <div v-if="ready">

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="本页覆盖 config.yml 的全部参数：加载时会自动补齐缺失键，保存时整文件写回。"
        style="margin-bottom: 14px"
      />

      <!-- ============ 数据存储 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">数据存储 (data)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="数据后端">
            <el-select v-model="model.data.mode" style="width: 260px">
              <el-option label="YAML（插件本地 data 目录）" value="yaml" />
              <el-option label="MySQL（SOYS DataSpec）" value="mysql" />
            </el-select>
            <div class="hint">yaml = plugins/WebGame/data/*.yml；mysql = SOYS DataSpec，需在 SOYS 启用 MySQL 存储后端。</div>
          </el-form-item>
        </el-form>
      </el-card>

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

      <!-- ============ Eagler 客户端 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">Eagler 客户端 (eagler)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="Eagler 默认 id">
            <el-input v-model="model.eagler['default-id']" placeholder="1.12.2" style="width: 320px" />
            <div class="hint">对应 /eagler/&lt;id&gt;/；玩家访问 /eagler/ 时进入这个客户端版本。</div>
          </el-form-item>
          <el-form-item label="Eagler 允许自选">
            <el-switch v-model="model.eagler['allow-player-select']" />
            <div class="hint">关闭后玩家访问 /eagler/ 只能进入默认版本，其他客户端页面不可访问。</div>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- ============ 客户端制品序号 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">客户端制品 (client-packages)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="下发预选制品 id">
            <el-input v-model="model['client-packages']['preselect-cloud-id']" placeholder="cloud-1（留空 = 不预选）" style="width: 320px" />
            <div class="hint">只用于「云游戏设备 → 更多操作 → 下发更新」弹窗的默认选中项，不会自动下发。</div>
          </el-form-item>
          <el-form-item label="下一个 Eagler 序号">
            <el-input-number v-model="model['client-packages']['next-eagler-id']" :min="1" :max="999999" controls-position="right" />
            <div class="hint">上传新的 Eagler 制品时自动分配；正常不需要手动修改。</div>
          </el-form-item>
          <el-form-item label="下一个云游戏序号">
            <el-input-number v-model="model['client-packages']['next-cloud-id']" :min="1" :max="999999" controls-position="right" />
            <div class="hint">上传新的云游戏制品时自动分配；正常不需要手动修改。</div>
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

      <!-- ============ 云游戏接入 ============ -->
      <el-card shadow="never" class="mb">
        <div slot="header">云游戏接入 (cloud)</div>
        <el-form label-width="170px" size="small">
          <el-form-item label="云游戏默认设备 id">
            <el-input v-model="model.cloud['default-id']" placeholder="留空 = 由服务端挑可用设备" style="width: 320px" />
            <div class="hint">对应 cloud-clients.yml 里的设备 id；allow-player-select=false 时只进这台设备。</div>
          </el-form-item>
          <el-form-item label="云游戏允许自选">
            <el-switch v-model="model.cloud['allow-player-select']" />
            <div class="hint">关闭后 /cloud/ 直接进入默认设备，不再显示设备选择页。</div>
          </el-form-item>
          <el-form-item label="下一个云设备 id">
            <el-input-number v-model="model.cloud['next-id']" :min="1" :max="999999" controls-position="right" />
            <div class="hint">新增/上报云设备时自动分配并回写；正常不需要手动修改。</div>
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
        '数据后端': 'data.mode：yaml = plugins/WebGame/data/*.yml；mysql = SOYS DataSpec，需启用 SOYS MySQL 存储后端。',
        'Eagler 默认 id': 'eagler.default-id：玩家访问 /eagler/ 时进入的客户端 id，对应 /eagler/<id>/。',
        'Eagler 允许自选': 'eagler.allow-player-select：false = 只允许进入默认 Eagler 版本。',
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
        '下发预选制品 id': 'client-packages.preselect-cloud-id：只用于“下发更新”弹窗的默认选中项，不会自动下发。',
        '下一个 Eagler 序号': 'client-packages.next-eagler-id：上传新 Eagler 制品时自动分配，正常无需修改。',
        '下一个云游戏序号': 'client-packages.next-cloud-id：上传新云游戏制品时自动分配，正常无需修改。',
        '启用自动进服': 'auto-join.enabled：记住过游戏名的浏览器打开页面即自动进服；没记过则弹框预填默认用户名。',
        '默认用户名': 'auto-join.default-username：只在没有记住游戏名时预填输入框，不是公共登录名（离线模式同名互踢）。',
        '云游戏默认设备 id': 'cloud.default-id：默认云设备 id；留空 = 由服务端从可用设备里挑。',
        '云游戏允许自选': 'cloud.allow-player-select：false = /cloud/ 直接进入默认设备，不显示选择页。',
        '下一个云设备 id': 'cloud.next-id：新增/上报云设备时自动分配并回写，正常无需修改。',
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
    // mixin 异步 load 完成后整体替换 model；此时补齐全部配置键，再放行渲染
    model(d) {
      if (d && typeof d === 'object') {
        this.ensureConfigDefaults(d)
        this.ready = true
      }
    }
  },
  methods: {
    /**
     * 补齐 config.yml 的全部参数。
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

      const data = ensure(d, 'data')
      setDefault(data, 'mode', 'yaml')

      const eagler = ensure(d, 'eagler')
      setDefault(eagler, 'default-id', '1.12.2')
      setDefault(eagler, 'allow-player-select', true)

      const page = ensure(d, 'page')
      setDefault(page, 'path', '/eagler/')
      setDefault(page, 'public-host', '127.0.0.1')
      setDefault(page, 'public-port', 0)

      const tunnel = ensure(d, 'tunnel')
      setDefault(tunnel, 'ws-path', '/eagler')
      setDefault(tunnel, 'loopback-host', '127.0.0.1')
      setDefault(tunnel, 'loopback-port', 0)
      setDefault(tunnel, 'allow-v3', true)
      setDefault(tunnel, 'allow-v4', true)
      setDefault(tunnel, 'min-mc-protocol', 340)
      setDefault(tunnel, 'max-mc-protocol', 340)
      setDefault(tunnel, 'server-brand', 'WebGame')
      setDefault(tunnel, 'server-version', '1.0.0')
      setDefault(tunnel, 'max-connections-per-device', 2)

      const autoJoin = ensure(d, 'auto-join')
      setDefault(autoJoin, 'enabled', true)
      setDefault(autoJoin, 'default-username', 'WebPlayer')

      const clientPackages = ensure(d, 'client-packages')
      setDefault(clientPackages, 'preselect-cloud-id', '')
      setDefault(clientPackages, 'next-eagler-id', 2)
      setDefault(clientPackages, 'next-cloud-id', 2)

      const cloud = ensure(d, 'cloud')
      setDefault(cloud, 'default-id', '')
      setDefault(cloud, 'allow-player-select', true)
      setDefault(cloud, 'next-id', 1)
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
