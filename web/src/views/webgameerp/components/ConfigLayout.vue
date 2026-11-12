<template>
  <div class="cfg-layout" :style="{ height: layoutHeight }" v-loading="loading">
    <config-top-bar v-if="!hideTopBar" :title="title" :file="file" :dirty="dirty" :loading="loading" :saving="saving" @reload="$emit('reload')" @save="$emit('save')" />
    <div class="cfg-body">
      <!-- 左侧电梯导航（自动从右侧内容扫描） -->
      <aside class="cfg-anchor">
        <div class="cfg-anchor-title">导航</div>
        <div v-for="item in anchors" :key="item.id" class="cfg-anchor-group">
          <a class="cfg-anchor-item" :class="{ active: activeId === item.id }" @click="scrollTo(item.id)">
            {{ item.label }}
          </a>
          <div v-if="item.children && item.children.length" class="cfg-anchor-sub">
            <a v-for="sub in item.children" :key="sub.id"
               class="cfg-anchor-sub-item" :class="{ active: activeId === sub.id }"
               @click="scrollTo(sub.id)">{{ sub.label }}</a>
          </div>
        </div>
      </aside>
      <!-- 中间配置内容 -->
      <div class="cfg-content" ref="content">
        <slot />
      </div>
      <!-- 右侧帮助面板：根据当前激活电梯项，汇总该节内所有配置项的 yml 注释 -->
      <aside class="cfg-help-panel">
        <div class="cfg-help-title">帮助说明</div>
        <div class="cfg-help-body" v-if="activeHelp.length">
          <div v-for="(h, i) in activeHelp" :key="i" class="cfg-help-item">
            <div class="cfg-help-key">{{ h.key }}</div>
            <div class="cfg-help-val">{{ h.val }}</div>
          </div>
        </div>
        <div class="cfg-help-empty" v-else>
          {{ activeLabel }}
        </div>
      </aside>
    </div>
  </div>
</template>

<script>
/**
 * 配置页通用布局：左侧电梯导航 + 中间配置内容 + 右侧固定帮助面板。
 *
 * 高度自适应：不写死 100vh / calc(100vh - 84px)——独立部署时页面位于 app-main
 * （顶部有 navbar + tags-view），wujie 微前端模式下则是子应用全视口。挂载后按
 * 「视口高度 - 自身距视口顶部距离」计算，两种宿主都能准确占满且不产生外层滚动条。
 */
import ConfigTopBar from './ConfigTopBar.vue'

const TOPBAR_H = 52 // 与 ConfigTopBar 高度一致，scrollSpy/scrollTo 用此偏移

// 表格纯操作列（无配置语义），帮助面板扫描时跳过
const ConfigLayoutIgnoreCol = new Set(['操作', '删', '删除', '更多'])

export default {
  name: 'ConfigLayout',
  components: { ConfigTopBar },
  props: {
    title: { type: String, required: true },
    file: { type: String, default: '' },
    dirty: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
    saving: { type: Boolean, default: false },
    hideTopBar: { type: Boolean, default: false },
    helpMap: { type: Object, default: () => ({}) }
  },
  data() {
    return {
      anchors: [],
      activeId: '',
      activeHelp: [],
      layoutHeight: ''
    }
  },
  computed: {
    activeLabel() {
      for (const g of this.anchors) {
        if (g.id === this.activeId) return g.label
        const sub = (g.children || []).find(s => s.id === this.activeId)
        if (sub) return sub.label
      }
      return ''
    }
  },
  mounted() {
    this.updateHeight()
    window.addEventListener('resize', this.updateHeight)
    this.buildAnchors()
    setTimeout(() => { this.buildAnchors() }, 500)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.updateHeight)
    if (this._observer) this._observer.disconnect()
  },
  methods: {
    /** 可用高度 = 视口高度 - 本组件距视口顶部距离（兼容 app-main 与 wujie 两种宿主） */
    updateHeight() {
      const el = this.$el
      if (!el || !el.getBoundingClientRect) return
      const top = el.getBoundingClientRect().top
      if (top < 0) return
      const h = Math.max(320, window.innerHeight - top)
      this.layoutHeight = h + 'px'
    },

    buildAnchors() {
      const root = this.$refs.content
      if (!root) return
      const list = []
      const cards = root.querySelectorAll('.el-card')
      cards.forEach((card, ci) => {
        const header = card.querySelector('.el-card__header')
        let label
        if (header) {
          label = header.textContent.trim()
        } else {
          const firstLabel = card.querySelector('.el-form-item__label')
          label = firstLabel ? firstLabel.textContent.trim() : '配置'
        }
        const cid = 'sec-' + ci
        card.id = cid
        // scroll-margin-top：让 scrollIntoView 后元素不被 sticky topbar 挡住
        card.style.scrollMarginTop = (TOPBAR_H + 8) + 'px'
        const group = { id: cid, label, children: [] }
        const dividers = card.querySelectorAll('.el-divider--horizontal')
        dividers.forEach((dv, di) => {
          const subLabel = dv.textContent.trim()
          if (!subLabel) return
          const sid = cid + '-sub-' + di
          dv.id = sid
          dv.style.scrollMarginTop = (TOPBAR_H + 8) + 'px'
          group.children.push({ id: sid, label: subLabel })
        })
        list.push(group)
      })
      this.anchors = list
      if (list.length && !this.activeId) this.activeId = list[0].id
      this.refreshActiveHelp()
      // 用 IntersectionObserver 接管 scrollSpy，不再用手动 scroll 事件遍历
      if (this._observer) this._observer.disconnect()
      this._visibleIds = new Set()
      this._observer = new IntersectionObserver((entries) => {
        entries.forEach(en => {
          if (en.isIntersecting) this._visibleIds.add(en.target.id)
          else this._visibleIds.delete(en.target.id)
        })
        // 在所有可见节中，选 top 最小的（最靠近判定带顶部的）
        let best = null
        this._visibleIds.forEach(id => {
          const el = document.getElementById(id)
          if (!el) return
          const top = el.getBoundingClientRect().top
          if (!best || top < best.top) best = { id, top }
        })
        if (best && best.id !== this.activeId) {
          this.activeId = best.id
          this.refreshActiveHelp()
        }
      }, {
        root,
        // 顶部留 topbar 高度，底部留 25% 缓冲：最后一节进入底部缓冲带时自动高亮
        rootMargin: (-TOPBAR_H - 10) + 'px 0px -25% 0px',
        threshold: 0
      })
      // observe 所有主节和子节
      list.forEach(g => {
        const el = document.getElementById(g.id)
        if (el) this._observer.observe(el)
        ;(g.children || []).forEach(s => {
          const el2 = document.getElementById(s.id)
          if (el2) this._observer.observe(el2)
        })
      })
    },
    scrollTo(id) {
      const el = document.getElementById(id)
      const sc = this.$refs.content
      if (el && sc) {
        // 立即设 activeId 给视觉反馈
        this.activeId = id
        this.refreshActiveHelp()
        // 手动计算 scrollTop，避免 scrollIntoView 冒泡到外层滚动容器导致 topbar 被滚走
        const target = el.offsetTop - (TOPBAR_H + 8)
        sc.scrollTo({ top: target, behavior: 'smooth' })
      }
    },
    /** 根据 activeId 找到对应的 DOM 节，扫描其内 form-item label / 表格列头，匹配 helpMap */
    refreshActiveHelp() {
      if (!this.activeId || !this.helpMap || !Object.keys(this.helpMap).length) return
      const el = document.getElementById(this.activeId)
      if (!el) { this.activeHelp = []; return }
      // 如果是子电梯（divider），帮助范围从 divider 到下一个 divider 或 card 末尾
      // 如果是主电梯（card），帮助范围是整个 card
      let scope = el
      let isDivider = false
      if (el.classList && el.classList.contains('el-divider')) {
        scope = this.collectDividerScope(el)
        isDivider = true
      }
      const items = []
      const seen = new Set()
      const collect = (txt) => {
        if (!txt || seen.has(txt)) return
        const hit = this.matchHelp(txt)
        if (hit) { seen.add(txt); items.push({ key: txt, val: hit }) }
      }
      // 1) 普通表单 label（el-form-item）
      scope.querySelectorAll('.el-form-item__label').forEach(lb => collect((lb.textContent || '').trim()))
      // 2) 表格列头（el-table），跳过纯操作列
      scope.querySelectorAll('.el-table__header .cell').forEach(c => {
        const txt = (c.textContent || '').trim()
        if (ConfigLayoutIgnoreCol.has(txt)) return
        collect(txt)
      })
      // 3) 卡片节内没有任何可扫描 label 时，回退用卡片标题（header）匹配 helpMap，
      //    覆盖纯表格/动态行/纯文本类配置节
      if (!items.length && !isDivider && el.classList && el.classList.contains('el-card')) {
        const header = el.querySelector('.el-card__header')
        if (header) collect(header.textContent.trim())
      }
      this.activeHelp = items
    },
    /** divider 的作用域：从 divider 到下一个 divider（或 card 结束） */
    collectDividerScope(dv) {
      const frag = document.createDocumentFragment()
      let node = dv.nextElementSibling
      while (node && !(node.classList && node.classList.contains('el-divider'))) {
        frag.appendChild(node.cloneNode(true))
        node = node.nextElementSibling
      }
      const wrap = document.createElement('div')
      wrap.appendChild(frag)
      return wrap
    },
    /** label 在 helpMap 中模糊匹配：key 末段 === label，或 label 包含末段 */
    matchHelp(labelText) {
      for (const [k, v] of Object.entries(this.helpMap)) {
        const last = k.split('.').pop()
        if (last === labelText || k === labelText || labelText.includes(last) || last.includes(labelText)) {
          return v
        }
      }
      return ''
    }
  }
}
</script>

<style scoped>
/* 占满可用高度：高度由 updateHeight 计算后内联注入 */
.cfg-layout {
  display: flex; flex-direction: column; overflow: hidden;
  padding: 0 !important; margin: 0 !important;
}
.cfg-body { display: flex; flex: 1; overflow: hidden; min-height: 0; }
.cfg-anchor {
  width: 180px; flex-shrink: 0; background: #fff; border-right: 1px solid #e4e7ed;
  padding: 16px 0 116px; overflow-y: auto;
}
.cfg-anchor-title { font-size: 12px; color: #909399; padding: 0 16px 8px; position: sticky; top: 0; background: #fff; }
.cfg-anchor-item {
  display: block; padding: 8px 16px; font-size: 13px; color: #606266; cursor: pointer;
  border-left: 3px solid transparent; transition: all .2s;
}
.cfg-anchor-item:hover { color: #409eff; background: #f5f7fa; }
.cfg-anchor-item.active { color: #409eff; border-left-color: #409eff; background: #ecf5ff; font-weight: 600; }
.cfg-anchor-sub { margin: 2px 0 4px; }
.cfg-anchor-sub-item {
  display: block; padding: 6px 16px 6px 30px; font-size: 12px; color: #909399; cursor: pointer;
  border-left: 3px solid transparent; transition: all .2s;
}
.cfg-anchor-sub-item:hover { color: #409eff; background: #f5f7fa; }
.cfg-anchor-sub-item.active { color: #409eff; border-left-color: #409eff; background: #ecf5ff; }
.cfg-content { flex: 1; padding: 16px 20px 116px; min-width: 0; overflow-y: auto; }

/* 右侧固定帮助面板 */
.cfg-help-panel {
  width: 280px; flex-shrink: 0; background: #f8f9fb; border-left: 1px solid #e4e7ed;
  display: flex; flex-direction: column; overflow: hidden;
}
.cfg-help-title {
  font-size: 12px; color: #909399; padding: 14px 16px 8px; border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
}
.cfg-help-body { padding: 10px 14px 110px; overflow-y: auto; flex: 1; }
.cfg-help-item { margin-bottom: 12px; }
.cfg-help-key { font-size: 12px; color: #409eff; font-weight: 600; margin-bottom: 2px; }
.cfg-help-val { font-size: 12px; color: #606266; line-height: 1.6; white-space: pre-wrap; }
.cfg-help-empty { padding: 24px 16px; font-size: 12px; color: #c0c4cc; text-align: center; }
</style>
