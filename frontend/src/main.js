import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {
  ArrowDown,
  Avatar,
  Box,
  Connection,
  Cpu,
  DataAnalysis,
  DataLine,
  Files,
  HomeFilled,
  List,
  Lock,
  Menu,
  Money,
  OfficeBuilding,
  SetUp,
  Switch as SwitchIcon,
  Tickets,
  TrendCharts,
  User,
  UserFilled,
  WarningFilled
} from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
// Element Plus 深色主题：所有 --el-* 变量挂在 html.dark 下，与自定义令牌共用同一开关
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import { useThemeStore } from './stores/theme'
import './styles/main.css'

/**
 * 图标只注册实际用到的那些：路由 meta.icon 里声明的 12 个 + 模板里直接书写的
 * ArrowDown / Lock / User + MainLayout 的兜底图标 Menu。
 *
 * 之前是 `import * as ElementPlusIconsVue` 全量注册（约 300 个图标），
 * 其中 95% 从未出现在界面上，却全部进了打包产物（构建后 element 分包里约 280KB 是图标）。
 * 这里逐个引入，Rollup 即可把没用到的图标摇掉；注册名与路由 meta 里的名字必须保持一致。
 */
const ICONS = {
  ArrowDown,
  Avatar,
  Box,
  Connection,
  Cpu,
  DataAnalysis,
  DataLine,
  Files,
  HomeFilled,
  List,
  Lock,
  Menu,
  Money,
  OfficeBuilding,
  // 路由 meta 写的是 'SetUp'（政策参数），必须同名注册，否则侧栏该菜单没有图标
  SetUp,
  // 路由 meta 写的是 'Switch'，模板用 <component :is="item.icon"> 按名字查找，必须同名注册
  Switch: SwitchIcon,
  Tickets,
  TrendCharts,
  User,
  UserFilled,
  // 养老金支出页的"统计口径可能不完整"提示图标
  WarningFilled
}

const app = createApp(App)
const pinia = createPinia()

for (const [name, component] of Object.entries(ICONS)) {
  app.component(name, component)
}

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 主题在挂载前初始化：首屏由 index.html 内联脚本防闪，这里让 store 与 <html> 的 class 对齐
useThemeStore(pinia).init()

app.mount('#app')
