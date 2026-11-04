<template>
  <el-container class="app-shell">
    <!-- ------------------------------ 侧栏：Element 菜单 ------------------------------ -->
    <el-aside width="236px" class="app-aside" :class="{ 'is-open': navOpen }">
      <div class="aside-brand">
        <div class="aside-mark" aria-hidden="true">链</div>
        <div class="aside-brand-text">
          <div class="aside-name">ChainSIP</div>
          <div class="aside-sub">区块链养老保险平台</div>
        </div>
      </div>

      <el-scrollbar class="aside-nav">
        <el-menu
          class="app-menu"
          router
          :default-active="route.path"
          :collapse="false"
          @select="navOpen = false">
          <el-menu-item-group v-for="group in groups" :key="group.title" :title="group.title">
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon || 'Menu'" /></el-icon>
              <span>{{ item.title }}</span>
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </el-scrollbar>

      <div class="aside-foot">
        <div class="foot-line">
          <span class="dot" :class="{ 'is-ok': chain && chain.ready }"></span>
          <span>{{ chain ? (chain.ready ? '链网关就绪' : '链网关未就绪') : '读取链网关…' }}</span>
        </div>
        <div class="foot-sub">
          group {{ chain?.groupId ?? '-' }} · {{ chain?.coreDeployed ? '核心合约已加载' : '核心合约未加载' }}
        </div>
      </div>
    </el-aside>

    <div v-if="navOpen" class="scrim" @click="navOpen = false"></div>

    <!-- ------------------------------ 右侧：状态条 + 内容 ------------------------------ -->
    <el-container class="app-body">
      <el-header height="56px" class="app-header">
        <button class="nav-toggle" type="button" aria-label="打开菜单" @click="navOpen = true">
          <el-icon><Menu /></el-icon>
        </button>

        <div class="hd-status" :title="chain?.message || '链网关状态'">
          <span class="dot" :class="{ 'is-ok': chain && chain.ready }"></span>
          <span class="hd-text">{{ chain ? (chain.ready ? '链上账本实时同步' : '链网关离线') : '正在连接链网关' }}</span>
          <span class="hd-sep" aria-hidden="true"></span>
          <span class="hd-num">区块 {{ blockNumber ?? '—' }}</span>
        </div>

        <div class="hd-right">
          <el-tooltip :content="theme.resolved === 'dark' ? '切换到浅色模式' : '切换到深色模式'" placement="bottom"
            :show-after="300">
            <button class="theme-toggle" type="button" :aria-label="theme.resolved === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
              @click="theme.toggle()">
              <el-icon><component :is="theme.resolved === 'dark' ? Sunny : Moon" /></el-icon>
            </button>
          </el-tooltip>

          <el-tooltip v-if="user.chainAddress" :content="user.chainAddress" placement="bottom" :show-after="300">
            <button class="hd-addr" type="button" @click="copyAddress(user.chainAddress)">
              {{ shortAddress(user.chainAddress) }}
            </button>
          </el-tooltip>
          <el-tag v-if="chainRole" type="success" effect="light" size="small" class="hd-role"
            :title="`链上角色：${chainRole}`">
            {{ chainRole }}
          </el-tag>

          <el-dropdown trigger="click" @command="onCommand">
            <button class="hd-user" type="button">
              <span class="hd-avatar" aria-hidden="true">{{ (user.displayName || '用').slice(0, 1) }}</span>
              <span class="hd-user-name">{{ user.displayName }}</span>
              <el-icon class="hd-caret"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="me">我的信息</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="app-main">
        <div class="main-inner" data-rise>
          <router-view />
        </div>
      </el-main>
    </el-container>

    <!-- ------------------------------ 我的信息 ------------------------------ -->
    <el-dialog v-model="meVisible" title="我的信息" width="640px" @closed="resetAccountForms">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="账号">{{ user.profile?.username }}</el-descriptions-item>
        <el-descriptions-item label="显示名">{{ user.profile?.displayName }}</el-descriptions-item>
        <el-descriptions-item label="链下角色">
          {{ user.profile?.role }}（{{ roleName(user.profile?.role) }}）
        </el-descriptions-item>
        <el-descriptions-item label="机构链账户编码">
          {{ user.profile?.institutionCode || '—（职工账号不需要）' }}
        </el-descriptions-item>
        <el-descriptions-item label="链上角色">
          {{ user.onChainRole || '未查询到（链未就绪或该地址未在链上注册）' }}
        </el-descriptions-item>
        <el-descriptions-item label="已绑定地址">
          <div v-if="(user.wallets || []).length === 0" class="text-muted">尚未绑定</div>
          <div v-for="w in user.wallets" :key="w.address" class="wallet-line">
            <span class="chain-address">{{ w.address }}</span>
            <el-tag :type="w.verified ? 'success' : 'info'" size="small">
              {{ w.verified ? '已验签' : '未验签' }}
            </el-tag>
            <el-tag v-if="w.onChainRole" type="warning" size="small">链上：{{ w.onChainRole }}</el-tag>
          </div>
        </el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">修改账号信息</el-divider>
      <el-form ref="profileFormRef" :model="profileForm" :rules="profileRules" label-width="100px">
        <el-form-item label="新用户名" prop="newUsername">
          <el-input v-model="profileForm.newUsername" placeholder="留空表示不修改，3-32 位字母/数字/下划线" />
        </el-form-item>
        <el-form-item label="新显示名" prop="newDisplayName">
          <el-input v-model="profileForm.newDisplayName" placeholder="留空表示不修改" />
        </el-form-item>
        <el-form-item label="当前密码" prop="password">
          <el-input v-model="profileForm.password" type="password" show-password
            placeholder="修改账号信息需要验证当前密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="profileUpdating" @click="submitProfile">保存账号信息</el-button>
          <span class="text-muted password-tip">修改后立即生效，无需重新登录。</span>
        </el-form-item>
      </el-form>

      <el-divider content-position="left">修改密码</el-divider>
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="100px">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password
            placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password
            placeholder="至少 6 位" @change="revalidateConfirm" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password
            placeholder="请再次输入新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="passwordChanging" @click="submitPassword">修改密码</el-button>
          <span class="text-muted password-tip">修改成功后需要用新密码重新登录。</span>
        </el-form-item>
      </el-form>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Moon, Sunny } from '@element-plus/icons-vue'
import { authApi, chainApi } from '../api'
import { useUserStore } from '../stores/user'
import { useThemeStore } from '../stores/theme'
import { copyText, roleName, shortAddress } from '../utils/format'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const theme = useThemeStore()

const meVisible = ref(false)
const navOpen = ref(false)

/* ---------------- 顶栏的链网关状态（每页都能看到账本是否在走） ---------------- */
const chain = ref(null)
const blockNumber = ref(null)
let timer = null

async function loadChainState() {
  try {
    chain.value = await chainApi.status()
  } catch (e) {
    chain.value = null
  }
  try {
    const block = await chainApi.block()
    blockNumber.value = block?.blockNumber ?? null
  } catch (e) {
    blockNumber.value = null
  }
}

onMounted(() => {
  loadChainState()
  // 链上出块是活的：顶栏的区块高度每 20 秒跟一次，不做轮询风暴
  timer = window.setInterval(loadChainState, 20000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})

async function copyAddress(text) {
  const ok = await copyText(text)
  if (ok) ElMessage.success('地址已复制')
  else ElMessage.warning('复制失败，请手动选中复制')
}

/* ---------------- 我的信息 → 修改账号信息 ---------------- */
const profileFormRef = ref(null)
const profileUpdating = ref(false)
const profileForm = reactive({ newUsername: '', newDisplayName: '', password: '' })

/** 新用户名：留空表示不修改；填了就校验 3-32 位字母 / 数字 / 下划线 */
function validateNewUsername(rule, value, callback) {
  if (!value) {
    callback()
  } else if (!/^[A-Za-z0-9_]{3,32}$/.test(value)) {
    callback(new Error('用户名只能包含字母、数字和下划线，长度 3-32 位'))
  } else {
    callback()
  }
}

const profileRules = {
  newUsername: [{ validator: validateNewUsername, trigger: 'blur' }],
  password: [{ required: true, message: '请输入当前密码', trigger: 'blur' }]
}

/** 弹窗关闭 / 提交成功后清空表单与校验状态 */
function resetProfileForm() {
  profileForm.newUsername = ''
  profileForm.newDisplayName = ''
  profileForm.password = ''
  profileFormRef.value?.clearValidate()
}

async function submitProfile() {
  const ok = await profileFormRef.value?.validate().catch(() => false)
  if (!ok) return
  // 两个新字段至少要填一个（都在 rules 之外单独判断，避免两条重复报错）
  if (!profileForm.newUsername && !profileForm.newDisplayName) {
    ElMessage.warning('请至少填写一项要修改的内容')
    return
  }
  profileUpdating.value = true
  try {
    const data = await authApi.updateProfile({
      newUsername: profileForm.newUsername,
      newDisplayName: profileForm.newDisplayName,
      password: profileForm.password
    })
    ElMessage.success('账号信息已修改')
    // 用后端返回值局部刷新 store 里的资料，界面立即生效，不需要重新登录
    const patch = {}
    if (data && data.username) patch.username = data.username
    if (data && data.displayName !== undefined && data.displayName !== null) {
      patch.displayName = data.displayName
    }
    if (Object.keys(patch).length) user.setProfile(patch)
    resetProfileForm()
  } catch (e) {
    // http.js 拦截器已按后端 message 原样弹过提示（400：当前密码不正确 / 该用户名已被占用 /
    // 用户名格式不合法 / 没有需要修改的内容），这里不再重复弹，避免两次 toast
  } finally {
    profileUpdating.value = false
  }
}

/* ---------------- 我的信息 → 修改密码 ---------------- */
const passwordFormRef = ref(null)
const passwordChanging = ref(false)
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

/** 确认新密码：必须与「新密码」一致 */
function validateConfirm(rule, value, callback) {
  if (!value) {
    callback(new Error('请再次输入新密码'))
  } else if (value !== passwordForm.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
  } else {
    callback()
  }
}

const passwordRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '新密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }]
}

/** 改过「新密码」后重新校验「确认新密码」，避免旧的校验结果残留 */
function revalidateConfirm() {
  if (passwordForm.confirmPassword) {
    passwordFormRef.value?.validateField('confirmPassword')
  }
}

/** 弹窗关闭时清空表单与校验状态 */
function resetPasswordForm() {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordFormRef.value?.clearValidate()
}

async function submitPassword() {
  const ok = await passwordFormRef.value?.validate().catch(() => false)
  if (!ok) return
  passwordChanging.value = true
  try {
    await authApi.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    ElMessage.success('密码已修改，请重新登录')
    meVisible.value = false
    // 旧 JWT 仍然有效，但改密后强制重新登录更安全（logout 已清 token 与本地资料）
    user.logout()
    router.push('/login')
  } catch (e) {
    // http.js 拦截器已按后端 message 原样弹过提示（400：原密码不正确 / 新密码至少 6 位 /
    // 新密码不能与原密码相同），这里不再重复弹，避免两次 toast
  } finally {
    passwordChanging.value = false
  }
}

/* ---------------- 菜单：路由是唯一来源，按 meta.group 编目 ---------------- */
const menus = computed(() => {
  const layout = router.options.routes.find((r) => r.path === '/')
  return (layout?.children || [])
    .filter((child) => {
      const roles = child.meta?.roles
      return !roles || roles.length === 0 || roles.includes(user.role)
    })
    .map((child) => ({
      path: '/' + child.path,
      title: child.meta?.title || child.path,
      group: child.meta?.group || '其他',
      icon: child.meta?.icon || 'Menu'
    }))
})

/** 同组连续排在一起，目录按角色过滤后自然收敛 */
const groups = computed(() => {
  const list = []
  for (const item of menus.value) {
    let bucket = list.find((g) => g.title === item.group)
    if (!bucket) {
      bucket = { title: item.group, items: [] }
      list.push(bucket)
    }
    bucket.items.push(item)
  }
  return list
})

function isActive(path) {
  return route.path === path
}

/** 职工地址在链上没有角色（返回 NONE），这种别拿来做徽标 */
const chainRole = computed(() => {
  const role = user.onChainRole
  return role && String(role).toUpperCase() !== 'NONE' ? role : null
})

function onCommand(command) {
  if (command === 'me') {
    // 每次打开都从空白开始，避免残留上次输入或错误提示
    resetAccountForms()
    meVisible.value = true
  } else if (command === 'logout') {
    user.logout()
    router.push('/login')
  }
}

/** 弹窗打开 / 关闭时统一重置「修改账号信息」与「修改密码」两个表单 */
function resetAccountForms() {
  resetProfileForm()
  resetPasswordForm()
}
</script>

<style scoped>
/* ------------------------------------------------ 外壳 */
.app-shell {
  height: 100%;
  background: var(--paper);
}

/* ------------------------------------------------ 侧栏 */

.app-aside {
  background: var(--nav-bg);
  border-right: 1px solid var(--hair);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  z-index: 30;
}

.aside-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 18px 14px;
  flex: none;
}

.aside-mark {
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: var(--accent);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
}

.aside-brand-text {
  min-width: 0;
}

.aside-name {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
  line-height: 1.2;
  color: var(--ink);
}

.aside-sub {
  font-size: 11px;
  color: var(--nav-muted);
  margin-top: 2px;
  white-space: nowrap;
}

.aside-nav {
  flex: 1;
  min-height: 0;
  padding: 0 8px;
}

/* 菜单：Element 内部结构需要 :deep 才吃得到 scoped 样式 */
:deep(.app-menu) {
  border-right: 0;
  background: transparent;
}

:deep(.app-menu .el-menu-item) {
  height: 36px;
  line-height: 36px;
  margin: 1px 0;
  padding-left: 12px !important;
  padding-right: 10px !important;
  border-radius: 7px;
  font-size: 13px;
  color: var(--nav-ink);
}

:deep(.app-menu .el-menu-item .el-icon) {
  width: 16px;
  margin-right: 9px;
  font-size: 15px;
  opacity: 0.75;
}

:deep(.app-menu .el-menu-item:hover) {
  background: var(--tone);
  color: var(--ink);
}

:deep(.app-menu .el-menu-item.is-active) {
  background: var(--nav-active-bg);
  color: var(--accent);
  font-weight: 600;
}

:deep(.app-menu .el-menu-item.is-active .el-icon) {
  opacity: 1;
}

:deep(.app-menu .el-menu-item-group__title) {
  padding: 14px 12px 4px !important;
  font-size: 10.5px;
  line-height: 1.4;
  letter-spacing: 0.06em;
  font-weight: 600;
  color: var(--nav-muted);
}

.aside-foot {
  flex: none;
  border-top: 1px solid var(--hair);
  padding: 11px 18px 13px;
  font-size: 11.5px;
  color: var(--nav-muted);
}

.foot-line {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--ink-2);
}

.foot-sub {
  margin-top: 3px;
  font-variant-numeric: tabular-nums;
}

.dot {
  width: 6px;
  height: 6px;
  flex: 0 0 6px;
  border-radius: 50%;
  background: var(--alert);
}

.dot.is-ok {
  background: var(--ok);
  box-shadow: 0 0 0 3px rgba(14, 122, 88, 0.14);
}

/* ------------------------------------------------ 右侧 */

.app-body {
  min-width: 0;
  min-height: 0;
}

.app-header {
  background: var(--surface);
  border-bottom: 1px solid var(--hair);
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 24px;
}

.nav-toggle {
  display: none;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--hair);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
}

.hd-status {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 12.5px;
  color: var(--ink-2);
}

.hd-text {
  white-space: nowrap;
}

.hd-sep {
  width: 1px;
  height: 13px;
  background: var(--hair);
}

.hd-num {
  font-variant-numeric: tabular-nums;
  color: var(--ink);
  font-weight: 600;
  white-space: nowrap;
}

.hd-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.hd-addr {
  border: 1px solid var(--hair-2);
  background: var(--tone);
  border-radius: 7px;
  padding: 4px 9px;
  font: inherit;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--ink-2);
  cursor: pointer;
  transition: color 0.16s ease, border-color 0.16s ease;
}

.hd-addr:hover {
  color: var(--accent);
  border-color: var(--accent-line);
}

.hd-role {
  flex: none;
}

.theme-toggle {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: none;
  border: 1px solid var(--hair);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink-2);
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease, background-color 0.18s ease;
}

.theme-toggle:hover {
  color: var(--accent);
  border-color: var(--accent-line);
}

.theme-toggle .el-icon {
  font-size: 15px;
}

.hd-user {
  display: flex;
  align-items: center;
  gap: 7px;
  border: 0;
  background: transparent;
  padding: 4px 2px;
  font: inherit;
  color: var(--ink);
  cursor: pointer;
}

.hd-avatar {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent);
  border: 1px solid var(--accent-line);
  display: grid;
  place-items: center;
  font-size: 11.5px;
  font-weight: 700;
}

.hd-user-name {
  font-size: 13px;
  font-weight: 600;
}

.hd-caret {
  font-size: 11px;
  color: var(--ink-3);
}

.hd-user:hover .hd-user-name {
  color: var(--accent);
}

.app-main {
  background: var(--paper);
  padding: 22px 24px 32px;
  overflow: auto;
}

.main-inner {
  max-width: 1360px;
}

/* ------------------------------------------------ 弹窗内 */

.wallet-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 3px 0;
}

.password-tip {
  margin-left: 12px;
  font-size: 12px;
}

/* ------------------------------------------------ 小屏 */

.scrim {
  display: none;
}

@media (max-width: 1000px) {
  .app-aside {
    position: fixed;
    inset: 0 auto 0 0;
    width: 236px;
    transform: translateX(-100%);
    transition: transform 0.24s cubic-bezier(0.16, 0.84, 0.3, 1);
  }

  .app-aside.is-open {
    transform: none;
  }

  .scrim {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(13, 23, 38, 0.4);
    z-index: 25;
  }

  .nav-toggle {
    display: flex;
  }

  .app-header {
    padding: 0 16px;
    gap: 10px;
  }

  .hd-text,
  .hd-num,
  .hd-sep,
  .hd-addr {
    display: none;
  }

  .app-main {
    padding: 16px 16px 40px;
  }
}
</style>
