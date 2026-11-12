# MCERP Vue2 示例工程（SOYSHTTPOverMC-ERP 前端）

> 面向开发者的 MCERP 微服务前端示例工程。基于
> [RuoYi-Vue2 v3.9.2](https://gitcode.com/yangzongzhuan/RuoYi-Vue2)
> （Vue2 + Element UI）二次改造，演示如何快速开发符合
> **MCERP / SOYS 平台契约**
> 的微服务管理页面。

SOYSHTTPOverMC-ERP 是 Minecraft 插件 **SOYSHTTPOverMC** 的 ERP 管理后台前端（经 **MCERP** 主应用登记菜单）。本工程既是该插件的前端，也是开发者上手 MCERP 微服务前端开发的**模板工程**：



* **双运行形态**：既支持独立部署（开发调试），也支持被 MCERP 主应用以 **wujie 微前端**方式嵌入（生产形态，无独立登录、无自带侧边栏）；

* **瘦身策略**：`vue / vuex / vue-router / element-ui` 通过 webpack `externals` **不打包**，运行时从主插件公共资源（`/common/*.min.js`）加载；

* **开箱即跑模板页**：自带 `/demo` 模板示例页（本地 mock 数据），新页面从这里复制改造即可。



***

## 1. 快速开始

### 环境要求



* Node.js ≥ 8.9（建议 14/16 LTS）、npm ≥ 3

### 安装与启动



```
\# 1. 安装依赖（国内镜像；或双击 bin/package.bat）

npm install --registry=https://registry.npmmirror.com

\# 2. 启动开发服务器（默认 http://localhost:80；或双击 bin/run-web.bat）

npm run dev

\# 3. 构建（或双击 bin/build.bat）

npm run build:prod   # 生产构建（SOYSHTTPOverMC-ERP 部署形态）

npm run build:stage  # 测试环境构建

\# 4. 本地预览生产产物（端口 9526）

npm run preview
```

**启动后立即打开模板页**：`http://localhost/demo`（开发者模板示例页，本地 mock 数据，不依赖后端接口）。

> **⚠️ 基础库依赖主插件公共资源**
> ：由于
> `externals`
> 瘦身，页面运行时从
> `/common/vue.min.js`
> 、
> `/common/element-ui.min.js`
> 等加载基础库（见
> `public/index.html`
> ）。开发 / 预览时需由后端或主插件伺服
> `/common`
> 资源，否则页面会因基础库 404 白屏。

### 常用环境变量（`.env.*` 文件，均可用环境变量覆盖）



| 变量                     | 默认值                                                    | 说明                              |
| ---------------------- | ------------------------------------------------------ | ------------------------------- |
| `VUE_APP_TITLE`        | `SOYSHTTPOverMC ERP`                                   | 网页标题                            |
| `VUE_APP_BASE_API`     | dev `/dev-api` / prod `/prod-api` / stage `/stage-api` | 开发代理前缀 / 生产 API 前缀              |
| `VUE_APP_PROXY_TARGET` | `http://localhost:8080`                                | **开发后端地址**（换后端机器 / 端口改这里，不用改代码） |
| `VUE_APP_PUBLIC_PATH`  | `/web/plugins/SOYSHTTPOverMC-ERP/`                     | 生产 publicPath（必须与插件名一致，见 FAQ）   |

### 开发模式代理说明

开发模式下 `/dev-api/xxx` 请求会被 dev-server 代理到 `VUE_APP_PROXY_TARGET`（默认 `http://localhost:8080`），并自动去掉 `/dev-api` 前缀。即 `request({ url: '/erp/user/list' })` 实际请求 `http://localhost:8080/erp/user/list`。

> **开始写页面之前，请先读完第 6 章「前缀拼接规则」**
> ：MCERP 下 API、页面、认证地址全部由前缀拼接而成，代码里只写相对路径，但必须知道最终 URL 长什么样（生产 / 开发各一套），否则容易写出生产环境 404 的路径。

## 2. 目录结构导读



```
MCERP-Vue2-example/

├── bin/                          # 常用脚本

│   ├── build.bat                 # 构建 dist（npm run build:prod）

│   ├── package.bat               # 安装依赖（npm install，npmmirror 源）

│   └── run-web.bat               # 启动开发服务器（npm run dev）

├── build/index.js                # --preview 本地预览服务（端口 9526）

├── public/

│   ├── index.html                # 契约注入 / soys-auth / externals 公共库引用

│   ├── __SOYS_CONTEXT__.js       # ★ SOYS 契约占位符（伺服时注入服务器环境原语，勿删）

│   └── favicon.ico               # 像素草方块图标

├── src/

│   ├── api/

│   │   ├── erp.js                # ★ 业务 API 封装范例（用户/权限组/APIKEY/配置/语言，路径 /erp/\*）

│   │   ├── login.js              # 认证委派（复用主插件 SoysAuth，勿直接改）

│   │   └── menu.js               # 后端菜单 getRouters

│   ├── soys/context.js           # ★ SOYS 平台契约消费封装（apiBaseURL / 服务器原语）

│   ├── views/soyshttpovermcerp/  # ★ 业务页面（目录严格对齐后端 SoysErpExpansion.menus()）

│   │   ├── demo/index.vue        # ★ 开发者模板页：本地 mock，开箱即跑（新页面从这里复制）

│   │   ├── user/index.vue        # 列表管理页范式（已接真实接口）

│   │   ├── group/index.vue       # 列表管理页范式（权限组）

│   │   ├── apikey/index.vue      # 列表管理页范式（X-API-KEY）

│   │   ├── lang/index.vue        # 语言包管理页

│   │   ├── config/               # 配置表单页范式（config/language/pages/eula + gateway/gw-\*）

│   │   ├── components/           # ConfigLayout（三栏布局）/ ConfigTopBar / ByteConverter

│   │   └── mixins/configPage.js  # 配置页通用 mixin（load/save/dirty 追踪）

│   ├── layout/

│   │   ├── index.vue             # 完整 Layout（独立部署：自带侧边栏/顶栏）

│   │   └── Empty.vue             # 空 Layout（wujie 模式：主应用提供侧边栏，只渲染页面）

│   ├── router/index.js           # ★ history 模式 + constantRoutes / wujieRoutes

│   ├── store/modules/permission.js # ★ 动态路由映射（wujie: URL → 本地视图、隐藏系统管理）

│   ├── utils/request.js          # axios 实例（baseURL = 契约 apiBaseURL）

│   ├── main.js                   # 入口：wujie 生命周期挂载 / 独立挂载

│   └── permission.js             # 路由守卫（wujie 模式直接放行，主应用统一鉴权）

├── vue.config.js                 # ★ publicPath / externals / gzip / splitChunks / dev 代理

├── .env.development / .env.production / .env.staging

└── package.json
```

> 标注 ★ 的是 MCERP 定制 / 重点文件；其余为若依原装基础设施，可放心复用。

## 3. 业务页面与后端菜单对齐

`src/views/soyshttpovermcerp/` 下的目录与后端 `SoysErpExpansion.menus()` 一一对应：



| 菜单                                                        | 路由                                       | 页面                        |
| --------------------------------------------------------- | ---------------------------------------- | ------------------------- |
| 游戏用户列表                                                    | `/soyshttpovermcerp/user`                | `user/index.vue`          |
| 权限组列表                                                     | `/soyshttpovermcerp/group`               | `group/index.vue`         |
| APIKEY 管理                                                 | `/soyshttpovermcerp/apikey`              | `apikey/index.vue`        |
| 语言管理                                                      | `/soyshttpovermcerp/lang`                | `lang/index.vue`          |
| 插件配置 → 核心配置                                               | `/soyshttpovermcerp/config/config`       | `config/config.vue`       |
| 插件配置 → 国际化                                                | `/soyshttpovermcerp/config/language`     | `config/language.vue`     |
| 插件配置 → 页面与资源                                              | `/soyshttpovermcerp/config/pages`        | `config/pages.vue`        |
| 插件配置 → 使用协议                                               | `/soyshttpovermcerp/config/eula`         | `config/eula.vue`         |
| 网关 → 总开关 / HTTPS / 认证鉴权 / 限流 / 访问限制 / IP 白名单 / TLS / 会话令牌 | `/soyshttpovermcerp/config/gateway/gw-*` | `config/gateway/gw-*.vue` |

**开发约定**：新增页面时，`.vue` 文件必须按后端 `menus()` 的 dir 结构存放（如 `config/gateway/`），否则导航 / 路由无法对齐。

## 4. 双模式运行架构（务必先理解）

同一套代码，通过 `window.__POWERED_BY_WUJIE__` **自动切换**两种形态：



```
┌──────────────────────────────┬──────────────────────────────────────────┐

│ ① 独立运行（npm run dev）       │ ② wujie 微前端嵌入（生产，MCERP 主应用）      │

├──────────────────────────────┼──────────────────────────────────────────┤

│ · 完整若依 Layout（自带侧边栏）  │ · EmptyLayout：主应用提供侧边栏，只渲染内容区   │

│ · 复用主插件 SOYS 登录          │ · 侧边栏直接使用本地 wujieRoutes（兜底菜单）    │

│   （SoysAuth：401 弹窗补登）    │ · 鉴权完全交给主应用，子应用直接放行           │

│ · 业务路由由后端 getRouters     │ · API 走契约 apiFullPrefix（运行时注入）      │

│   动态下发（wujie: URL 自动映射）│ · 401/403 → 主插件 SoysAuth 弹登录窗         │

└──────────────────────────────┴──────────────────────────────────────────┘
```

### 路由策略（`src/router/index.js`）



* **公共路由 **`constantRoutes`：登录 / 注册 / 404 / 首页 / 锁屏，以及**开发者模板页 **`/demo`（所有模式加载，独立模式侧边栏可见）；

* **wujie 兜底路由 **`wujieRoutes`：业务路由 `/soyshttpovermcerp/*`，仅在 `isWujie=true` 时合并进 router，作为主应用菜单的下发兜底（`main.js` 中 `SET_SIDEBAR_ROUTERS(wujieRoutes)`）；

* **独立模式业务菜单**：由后端 `getRouters` 动态下发（`store/modules/permission.js`），组件路径支持 `wujie:` 前缀 URL 自动映射到本地 `src/views/soyshttpovermcerp/**`。

**开发者不需要在业务代码里做任何 wujie 相关的处理**，路由、鉴权、通信都已封装好。

## 5. 如何开发一个新页面（Step-by-Step）

以新增「商品管理」模块为例，完整路径如下：

### 第 1 步：封装 API（`src/api/` 新建文件）



```
// src/api/goods.js

import request from '@/utils/request'

export function listGoods(params) {

  return request({ url: '/erp/goods/list', method: 'get', params })

}

export function addGoods(data) {

  return request({ url: '/erp/goods/add', method: 'post', data })

}

export function updateGoods(data) {

  return request({ url: '/erp/goods/update', method: 'post', data })

}

export function delGoods(id) {

  return request({ url: '/erp/goods/remove', method: 'post', data: { id } })

}
```

> 路径需与后端 Controller 类级前缀（
> `/erp/goods/*`
> ）一一对应。baseURL 已由契约自动注入，无需关心环境差异（详见第 6 章）。

### 第 2 步：注册路由



* **wujie 模式**：在 `src/router/index.js` 的 `wujieRoutes`（`/soyshttpovermcerp` 的 children）中追加：



```
{ path: 'goods', component: () => import('@/views/soyshttpovermcerp/goods/index'), name: 'ErpGoods', meta: { title: '商品管理' } },
```



* **独立模式**：菜单由后端 `getRouters` 动态下发，前端只需保证视图文件存在于 `src/views/soyshttpovermcerp/**`（后端 component 传 `wujie:` 前缀 URL 时自动映射到本地视图）。开发阶段临时调试，也可参照 `/demo` 路由在 `constantRoutes` 里加一条直达路由。

### 第 3 步：编写列表页（复制模板最快）

复制 `src/views/soyshttpovermcerp/demo/index.vue` → `src/views/soyshttpovermcerp/goods/index.vue`，把本地 mock 替换为第 1 步的接口调用（页面内注释已标明替换点）。

### 第 4 步：如需配置表单页

参考 `src/views/soyshttpovermcerp/config/gateway/gw-https.vue`：使用 `ConfigLayout` 三栏布局 + `configPage` mixin，只需提供 `fileId` 并编写 `el-card` 分节表单。

### 第 5 步：本地验证



```
npm run dev   # 打开 http://localhost/demo 对照模板；业务页按路由访问验证

npm run build:prod   # 构建通过后再提交
```

## 6. 前缀拼接规则（URL 组装全景）—— 开发前必读

MCERP 环境下，前端大量依赖**前缀拼接**来定位后端接口、页面资源与登录地址。这些前缀来自 SOYS 契约注入的服务器环境原语（`window.SOYS_CONTEXT`，伺服时由 ContractInjector 替换）。正确理解组装规则，才能写出生产 / 开发环境都正确的路径 ——**开发时只需要写相对路径，前缀全部由框架拼接，但必须知道拼接结果长什么样。**

### 6.1 契约字段与取值（伺服时注入，示例值）



| 契约字段                   | 含义                                                     | 示例值（本插件）                               |
| ---------------------- | ------------------------------------------------------ | -------------------------------------- |
| `apiPrefix`            | SOYS API 总前缀                                           | `/api`                                 |
| `pluginsPrefix`        | 插件命名空间前缀                                               | `/plugins`                             |
| `apiFullPrefix`        | 当前插件**完整 API 前缀** = `apiPrefix + pluginsPrefix + /插件名` | `/api/plugins/SOYSHTTPOverMC-ERP`      |
| `pageFullPrefix`       | 当前插件**页面资源前缀** = `/web + pluginsPrefix + /插件名`         | `/web/plugins/SOYSHTTPOverMC-ERP`      |
| `webResourcePrefix`    | 页面静态资源内部前缀                                             | `web/plugins/SOYSHTTPOverMC-ERP/page/` |
| `scheme / host / port` | 服务器地址                                                  | `http` / `localhost` / `8080`          |

> 旧字段
> `fullPrefix`
> /
> `pagePrefix`
> 已废弃，不要再使用（
> `src/soys/context.js`
> 已按 v4 契约消费）。

### 6.2 三类 URL 的组装公式

**① 业务 API 请求**（`src/api/erp.js` 等，代码中只写相对路径）



```
生产：origin + apiFullPrefix + 控制器前缀 + 接口路径

     = http://<host>:<port>/api/plugins/SOYSHTTPOverMC-ERP/erp/user/list

开发：/dev-api + 控制器前缀 + 接口路径（dev-server 代理去掉 /dev-api 后转发）

     = http://localhost:8080/erp/user/list
```

**② 页面 / 路由地址**（history 模式，路由 base 已自动设置）



```
生产：pageFullPrefix + 路由路径 = /web/plugins/SOYSHTTPOverMC-ERP/soyshttpovermcerp/user

开发：/ + 路由路径             = /soyshttpovermcerp/user
```

**③ 认证**（复用主插件 SoysAuth，`src/api/login.js`）



```
登录/登出：origin + apiPrefix + /auth/login|logout

         = http://<host>:<port>/api/auth/login

会话信息：/api/plugins/MCERP/auth/getInfo|getRouters（MCERP 主插件命名空间）
```

### 6.3 生产 / 开发对照（以 `/erp/user/list` 为例）



| 环节            | 生产（wujie 嵌入）                                                | 开发（npm run dev）                           |
| ------------- | ----------------------------------------------------------- | ----------------------------------------- |
| axios baseURL | 契约 `apiFullPrefix` = `/api/plugins/SOYSHTTPOverMC-ERP`      | `/dev-api`                                |
| 转发方式          | 网关直接路由到后端控制器                                                | dev-server 代理，`pathRewrite` 去掉 `/dev-api` |
| 最终请求          | `http://<服务器>/api/plugins/SOYSHTTPOverMC-ERP/erp/user/list` | `http://localhost:8080/erp/user/list`     |
| 页面地址          | `/web/plugins/SOYSHTTPOverMC-ERP/soyshttpovermcerp/user`    | `/soyshttpovermcerp/user`                 |

### 6.4 拼接链路图（从前端代码到后端）



```
代码中写的路径：'/erp/user/list'

      │

      ▼

request.js baseURL：生产 = 契约 apiFullPrefix（/api/plugins/SOYSHTTPOverMC-ERP）

                    开发 = /dev-api（VUE_APP_BASE_API）

      │

      ├── 生产：http://<host>:<port> /api/plugins/SOYSHTTPOverMC-ERP /erp/user/list

      │                （网关直接路由，前缀与后端 registerController 类级前缀一一对应）

      │

      └── 开发：/dev-api/erp/user/list

                     │  dev-server 代理（vue.config.js）pathRewrite ^/dev-api → ''

                     ▼

           http://localhost:8080/erp/user/list   （VUE_APP_PROXY_TARGET 可覆盖目标）
```

### 6.5 注意事项与坑（开发时必须知道）



1. **控制器前缀直接挂 **`apiFullPrefix`** 下**：MCERP 后端已从 `registerProxyController` 改为 `registerController` 正常登记，`/erp/*`、`/auth`、`/system/*` 等类级前缀直接拼接在 `/api/plugins/SOYSHTTPOverMC-ERP` 之后。**不要再拼 **`/prod-api`（历史契约段已废弃，`src/soys/context.js` 中 `appApi` 仅保留不再参与拼接）。

2. **写路径只写相对路径**：API 写 `/erp/xxx`（前导斜杠），路由写 `soyshttpovermcerp/xxx`（无前导斜杠），前缀全部由框架拼接，不要手拼 `host:port`。

3. **生产环境 **`.env.production`** 的 **`VUE_APP_BASE_API`**（**`/prod-api`**）不参与拼接**：生产 baseURL 一律取自契约 `apiFullPrefix`；`VUE_APP_BASE_API` 只在开发模式作为代理前缀使用。

4. `MCERP_HOST_API`**（**`/api/plugins/MCERP`**）是代码中硬编码的主插件命名空间**（`src/api/login.js`）：若契约 `apiPrefix` 或主插件名变化，需要同步修改此处，否则 `getInfo/getRouters` 会 401。

5. **页面资源一律相对路径**（`./static/...`、`./index.html`）：`pageFullPrefix` 只用于显式拼页面前缀的场景（如路由 base），不要在页面里手拼绝对资源路径。

6. `publicPath`**、路由 base、wujie 加载 URL 三者必须一致**（都依赖插件名 `SOYSHTTPOverMC-ERP`）：改插件名必须同步设置 `VUE_APP_PUBLIC_PATH` 并重新构建，否则深层 URL 刷新 404 白屏。

7. `/dev-api`**、**`/prod-api`**、**`/api`** 在 **`SOYS_CONTEXT_EXCLUDES`** 中**（`public/__SOYS_CONTEXT__.js` 的 HTML 资源引用改写排除列表）：新增应用级 API 前缀时必须同步加入该数组，避免被 ContractInjector 改写为插件资源路径。

8. **路由为 history 模式**：SOYS 1.4.0+ 支持 `spaFallback`（深层刷新不 404）；若部署在旧版本 SOYS 上，需在 `src/soys/context.js` 的 `capabilities.spaFallback` 置为 `false` 并改用 hash 模式。

## 7. 页面开发范式（三种，全部有现成实例）



| 范式         | 适用场景        | 参考文件                                                      | 关键组成                                                                  |
| ---------- | ----------- | --------------------------------------------------------- | --------------------------------------------------------------------- |
| **列表管理页**  | 数据 CRUD     | `views/soyshttpovermcerp/demo/index.vue`、`user/index.vue` | 搜索表单 + el-table + Pagination + right-toolbar + 操作弹窗                   |
| **配置表单页**  | yml / 配置类编辑 | `views/soyshttpovermcerp/config/**`、`gateway/gw-*.vue`    | `ConfigLayout`（电梯导航 + 内容 + 帮助面板）+ `configPage` mixin（load/save/dirty） |
| **API 封装** | 所有请求        | `src/api/erp.js`                                          | 统一 `request` 封装，一个函数一个接口                                              |

### 常用组件



| 组件            | 位置                             | 说明                                                     |
| ------------- | ------------------------------ | ------------------------------------------------------ |
| ConfigLayout  | `components/ConfigLayout.vue`  | 配置页通用布局：左侧电梯导航 + 中间表单 + 右侧帮助面板（帮助说明按当前激活电梯节匹配 helpMap） |
| ConfigTopBar  | `components/ConfigTopBar.vue`  | 配置页顶部栏（重新加载 / 保存并热重载），常显悬挂                             |
| ByteConverter | `components/ByteConverter.vue` | 字节 ↔ 单位（B/KB/MB/GB/TB）双向换算组件，用于「（字节）」类配置项              |

### 全局可用工具（若依内置，直接使用）



* `this.$modal.msgSuccess / msgError / msgConfirm`：消息提示

* `this.parseTime` / `this.resetForm` / `this.handleTree` / `this.selectDictLabel`：格式化与工具

* 全局组件：`<Pagination>`、`<RightToolbar>`、`<FileUpload>`、`<ImageUpload>`、`<Editor>` 等

* `v-hasPermi` 权限指令（`directive/permission`）

## 8. 构建与部署



* **publicPath（关键）**：生产环境默认绝对前缀 `/web/plugins/SOYSHTTPOverMC-ERP/`（wujie + history 模式下深层 URL 必须绝对化，否则异步 chunk 404 白屏）；可用 `VUE_APP_PUBLIC_PATH` 覆盖；

* **产物**：`dist/`（JS/CSS 已 gzip 压缩，基础库经 `externals` 不打包）；

* **部署**：把 `dist/*` 复制到插件工程 `src/main/resources/dist/`，随 jar 打包；插件启动后资源经 `webResourcePrefix` 伺服。伺服时 `__SOYS_CONTEXT__.js` 的占位符被 ContractInjector 自动替换为服务器环境原语；

* **切勿改动 **`public/__SOYS_CONTEXT__.js`** 的结构**（`window.SOYS_CONTEXT` 与 `SOYS_CONTEXT_EXCLUDES` 是契约注入点）；

* **主应用接入**：以 wujie 方式加载子应用入口 `index.html`，传入 `props.path`（初始路由，如 `/soyshttpovermcerp/user`）；主应用菜单切换通过 bus 发 `router-push` 事件。

## 9. 常见问题（FAQ）



1. **后端地址在哪改？** 开发：`.env.development` 的 `VUE_APP_PROXY_TARGET`（或环境变量）。生产：不需要改 ——API 地址由 SOYS 契约运行时注入，自动适配当前服务器。

2. **页面白屏 / 基础库 404？** 检查 `/common/*.min.js`（externals 公共库）与 `/soys-auth.js` 是否由后端 / 主插件伺服；这是当前版本瘦身策略的运行前提。

3. **独立模式（npm run dev）看不到业务菜单？** 业务菜单由后端 `getRouters` 动态下发，需登录且后端返回菜单后侧边栏才显示。开发调试模板可先访问 `/demo`（本地 mock）；临时调试业务页可参照 `/demo` 在 `constantRoutes` 加一条直达路由。

4. **wujie 模式下侧边栏菜单来自哪里？** 来自本地 `wujieRoutes`（`src/router/index.js`，`/soyshttpovermcerp/*`），主应用加载时通过 `SET_SIDEBAR_ROUTERS` 注入，作为主应用菜单的下发兜底。

5. **访问页面报 401 / 弹登录窗？** 正常行为。wujie 模式由主应用统一鉴权，登录窗来自主插件 SoysAuth，登录后自动刷新重发请求；独立模式复用主插件 SOYS 登录（`/soys-auth.js`）。

6. **深层 URL 刷新 404 / 白屏？** 确认生产 `publicPath` 为绝对前缀、路由 `base` 与契约 `pageFullPrefix` 一致；SOYS 1.4.0+ 已实现 spaFallback。

7. **页面 404 打不开？** 确认 `.vue` 文件存放目录与后端 `menus()` 的 dir 结构一致（如 `config/gateway/`），且 `wujieRoutes` 中已登记对应路由。

8. **帮助说明不显示？** 帮助面板按「当前激活电梯节」扫描该节内 form-item label 匹配 helpMap，需点击左侧导航切换到对应节。

9. **wujie 嵌入后菜单内容错乱 / 显示上一页缓存？** 主应用侧 wujie 容器需要正确保活 / 切换；子应用侧每次 `__WUJIE_MOUNT` 重建实例。硬刷新（Ctrl+F5）可排除缓存干扰。

## 10. 致谢



* 基础框架：[RuoYi-Vue2 v3.9.2](https://gitcode.com/yangzongzhuan/RuoYi-Vue2)（MIT License）

* 微前端方案：[wujie](https://github.com/Tencent/wujie)