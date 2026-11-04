import { defineStore } from 'pinia'

const THEME_KEY = 'chainsip-theme'

/** 系统是否偏好深色（跟随系统档位时用） */
function systemPrefersDark() {
  return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches)
}

/** mode → 实际生效主题 */
function resolve(mode) {
  if (mode === 'dark') return 'dark'
  if (mode === 'light') return 'light'
  return systemPrefersDark() ? 'dark' : 'light'
}

/**
 * 把生效主题写到 <html> 上。Element Plus 的深色主题以 `html.dark` 为开关
 * （dark/css-vars.css 内部所有变量都挂在 html.dark 下），本项目自定义令牌也挂在同一下。
 */
function apply(theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

/**
 * 主题状态。mode 三档：light / dark / system。
 *
 * 首次访问默认 `system`（跟随操作系统），顶栏按钮切换的是「浅色 ↔ 深色」两个显式值；
 * 一旦手动切换即为显式选择并写入 localStorage。
 */
export const useThemeStore = defineStore('theme', {
  state: () => ({
    mode: localStorage.getItem(THEME_KEY) || 'system'
  }),

  getters: {
    /** 当前实际生效的主题（system 会被解析成 light / dark） */
    resolved: (state) => resolve(state.mode)
  },

  actions: {
    /**
     * 应用主题并监听系统偏好变化。首屏已由 index.html 的内联脚本防闪，
     * 这里负责把 store 状态与 <html> 上的 class 对齐，并让「跟随系统」实时响应。
     */
    init() {
      apply(this.resolved)
      if (window.matchMedia) {
        const mq = window.matchMedia('(prefers-color-scheme: dark)')
        const onChange = () => {
          if (this.mode === 'system') apply(this.resolved)
        }
        if (mq.addEventListener) mq.addEventListener('change', onChange)
        else if (mq.addListener) mq.addListener(onChange)
      }
    },

    setMode(mode) {
      this.mode = mode
      localStorage.setItem(THEME_KEY, mode)
      apply(this.resolved)
    },

    /** 顶栏按钮：浅色 ↔ 深色 */
    toggle() {
      this.setMode(this.resolved === 'dark' ? 'light' : 'dark')
    }
  }
})
