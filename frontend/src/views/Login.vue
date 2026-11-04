<template>
  <div class="login">
    <button class="theme-toggle" type="button"
      :aria-label="theme.resolved === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
      :title="theme.resolved === 'dark' ? '切换到浅色模式' : '切换到深色模式'" @click="theme.toggle()">
      <el-icon><component :is="theme.resolved === 'dark' ? Sunny : Moon" /></el-icon>
    </button>

    <!-- ------------------------------- 左：账本封面 ------------------------------- -->
    <section class="book">
      <header class="book-head" data-rise>
        <span class="seal" aria-hidden="true">链</span>
        <span class="book-name">ChainSIP</span>
        <span class="book-sub">区块链养老保险平台</span>
      </header>

      <div class="book-main">
        <h1 class="book-title" data-rise style="--d: 60ms">
          每一笔养老钱<br />链上与链下都对得上
        </h1>

        <p class="book-lead" data-rise style="--d: 140ms">
          缴费、权益、关系转移逐笔记入联盟链；链下只保存认证与索引，账目一律以合约状态为准。
        </p>

        <div class="rules">
          <p class="rules-head" data-rise style="--d: 220ms">合约自证的三段恒等式</p>
          <div v-for="(rule, index) in identities" :key="rule.left" class="rule-row" data-rise
            :style="{ '--d': 280 + index * 70 + 'ms' }">
            <span class="rule-left">{{ rule.left }}</span>
            <span class="rule-op">=</span>
            <span class="rule-right">{{ rule.right }}</span>
          </div>
        </div>
      </div>

      <footer class="book-foot" data-rise style="--d: 520ms">
        <span class="dot" :class="{ 'is-ok': health && health.chain?.ready }"></span>
        <span class="foot-text">{{ healthText }}</span>
        <span class="foot-sep" aria-hidden="true">·</span>
        <span class="foot-num">区块 {{ health?.chain?.blockNumber ?? '—' }}</span>
        <span class="foot-sep" aria-hidden="true">·</span>
        <span class="foot-num">group {{ health?.chain?.groupId ?? '—' }}</span>
      </footer>
    </section>

    <!-- ------------------------------- 右：登录取号 ------------------------------- -->
    <section class="entry">
      <div class="entry-wrap">
        <h2 class="entry-title" data-rise style="--d: 80ms">登录</h2>
        <p class="entry-sub" data-rise style="--d: 130ms">链下认证，链上记账。</p>

        <el-form ref="formRef" :model="form" :rules="formRules" label-position="top" class="entry-form"
          @keyup.enter="onSubmit">
          <el-form-item label="账号" prop="username" data-rise style="--d: 180ms">
            <el-input v-model="form.username" placeholder="用户名" size="large" clearable
              autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码" prop="password" data-rise style="--d: 230ms">
            <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password
              autocomplete="current-password" />
          </el-form-item>
          <el-button type="primary" size="large" class="entry-submit" :loading="loading" data-rise
            style="--d: 280ms" @click="onSubmit">
            登录
          </el-button>
        </el-form>

        <div class="demo" data-rise style="--d: 350ms">
          <p class="demo-head">演示账号<span class="demo-tip">点一行即可填入</span></p>
          <button v-for="item in demoAccounts" :key="item.username" class="demo-row" type="button"
            @click="fill(item)">
            <span class="demo-user">{{ item.username }}</span>
            <span class="demo-role">{{ item.label }}</span>
            <span class="demo-pass">{{ item.password }}</span>
          </button>
        </div>

        <p class="entry-note" data-rise style="--d: 420ms">
          认证发生在链下：密码只以 PBKDF2 派生值存放在数据库，绝不上链。链上只认交易签名地址，
          每个账号创建时自动生成并绑定自己的链上地址。
        </p>

        <footer class="entry-foot" data-rise style="--d: 480ms">
          <span class="foot-item">
            <span class="foot-key">核心合约</span>
            <span class="foot-val num">{{ shortAddress(health?.chain?.coreContract) }}</span>
          </span>
          <span class="foot-item">
            <span class="foot-key">转移合约</span>
            <span class="foot-val num">{{ shortAddress(health?.chain?.transferContract) }}</span>
          </span>
        </footer>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Moon, Sunny } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'
import { useThemeStore } from '../stores/theme'
import { shortAddress } from '../utils/format'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const theme = useThemeStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const formRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

/* ---------------- 账本结构：与合约 SystemStats 的字段一一对应 ---------------- */
const identities = [
  { left: '累计注资', right: '单位统筹余额 + 累计已缴' },
  { left: '累计已缴', right: '个人账户 + 城市基金池 + 在途托管' },
  { left: '城市基金池 + 在途统筹', right: '职工统筹权益' }
]

/* ---------------- 演示账号（本地测试链真实存在，密码仅用于演示） ---------------- */
const demoAccounts = [
  { username: 'admin', password: 'ChangeMe_123456', label: '平台管理员' },
  { username: 'bureau_demo', password: 'ChangeMe_123456', label: '社保经办机构（示例）' },
  { username: 'sponsor_demo', password: 'ChangeMe_123456', label: '缴费单位' },
  { username: 'employee_demo', password: 'ChangeMe_123456', label: '参保职工' },
  { username: 'bureau_demo2', password: 'ChangeMe_123456', label: '社保经办机构（示例2）' },
  { username: 'sponsor_demo2', password: 'ChangeMe_123456', label: '缴费单位（示例2）' },
  { username: 'regulator_demo', password: 'ChangeMe_123456', label: '监管账户' }
]

function fill(item) {
  form.username = item.username
  form.password = item.password
}

/* ---------------- 登录前就能看到账本是否在走 ----------------
   直接 fetch，不经 http.js：登录页不需要弹任何后端错误提示。 */
const health = ref(null)
let timer = null

async function loadHealth() {
  try {
    const response = await fetch('/api/public/health')
    const body = await response.json()
    health.value = body && body.code === 0 ? body.data : null
  } catch (e) {
    health.value = null
  }
}

const healthText = computed(() => {
  if (!health.value) return '正在连接后端'
  if (health.value.chain?.ready) return '链上账本运行中'
  return '后端在线，链网关未就绪'
})

onMounted(() => {
  loadHealth()
  timer = window.setInterval(loadHealth, 10000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})

async function onSubmit() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  loading.value = true
  try {
    await user.login({ ...form })
    ElMessage.success('登录成功')
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' && redirect ? redirect : '/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.02fr) minmax(0, 1fr);
  height: 100%;
  background: var(--paper);
}

.theme-toggle {
  position: absolute;
  top: 16px;
  right: 16px;
  z-index: 5;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--hair);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink-2);
  cursor: pointer;
  box-shadow: var(--el-box-shadow-lighter);
  transition: color 0.18s ease, border-color 0.18s ease, background-color 0.18s ease;
}

.theme-toggle:hover {
  color: var(--accent);
  border-color: var(--accent-line);
}

.theme-toggle .el-icon {
  font-size: 16px;
}

/* --------------------------------------------------- 左：账本 */

.book {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 40px 56px 32px;
  background: var(--field);
  color: var(--field-ink);
  overflow: hidden;
}

.book-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.seal {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  background: var(--accent);
  color: #f4f7f4;
  font-family: var(--font-display);
  font-size: 18px;
  line-height: 1;
  padding-bottom: 2px;
}

.book-name {
  font-family: var(--font-display);
  font-size: 17px;
  letter-spacing: 0.08em;
}

.book-sub {
  font-size: 13px;
  color: var(--field-ink-2);
  letter-spacing: 0.04em;
}

.book-main {
  margin: auto 0;
  padding: 48px 0 40px;
  max-width: 620px;
}

.book-title {
  margin: 0;
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(28px, 4vw, 56px);
  line-height: 1.22;
  letter-spacing: 0.01em;
}

.book-lead {
  margin: 22px 0 0;
  font-size: 15px;
  line-height: 1.75;
  color: rgba(242, 244, 239, 0.74);
  max-width: 46ch;
}

.rules {
  margin-top: 42px;
}

.rules-head {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--field-ink-2);
  letter-spacing: 0.06em;
}

.rule-row {
  display: grid;
  grid-template-columns: 152px 18px minmax(0, 1fr);
  align-items: baseline;
  gap: 6px;
  padding: 10px 0;
  font-size: 13.5px;
}

.rule-left {
  color: rgba(242, 244, 239, 0.62);
}

.rule-op {
  color: var(--field-ink-2);
  text-align: center;
}

.rule-right {
  color: rgba(242, 244, 239, 0.92);
}

.book-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--field-ink-2);
  font-variant-numeric: tabular-nums;
}

.foot-text {
  color: rgba(242, 244, 239, 0.78);
}

.foot-sep {
  opacity: 0.4;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--alert);
}

.dot.is-ok {
  background: #35b98a;
}

/* --------------------------------------------------- 右：表单 */

.entry {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 40px 28px;
  overflow-y: auto;
}

.entry-wrap {
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 372px;
}

.entry-form {
  margin-top: 0;
}

.entry-form :deep(.el-form-item.is-required .el-form-item__label::before) {
  display: none;
}

.entry-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: 42px;
}

.foot-item {
  display: flex;
  align-items: baseline;
  gap: 7px;
  font-size: 13px;
}

.foot-key {
  color: var(--ink-3);
}

.foot-val {
  color: var(--ink-2);
}

.entry-title {
  margin: 0;
  font-family: var(--font-display);
  font-weight: 400;
  font-size: 26px;
  letter-spacing: 0.04em;
}

.entry-sub {
  margin: 6px 0 30px;
  font-size: 14px;
  color: var(--ink-2);
}

.entry-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.entry-submit {
  width: 100%;
  height: 44px;
  font-size: 15px;
  letter-spacing: 0.28em;
  margin-top: 6px;
}

.demo {
  margin-top: 34px;
}

.demo-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 0 0 8px;
  font-size: 13.5px;
  color: var(--ink-2);
}

.demo-tip {
  color: var(--ink-3);
}

.demo-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 84px 84px;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 9px 10px;
  border: 0;
  background: transparent;
  font: inherit;
  text-align: left;
  color: var(--ink);
  cursor: pointer;
  border-radius: var(--radius);
  transition: background-color 0.16s ease;
}

.demo-row:hover {
  background: var(--surface);
}

.demo-user {
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

.demo-role {
  font-size: 13px;
  color: var(--ink-3);
  text-align: right;
}

.demo-pass {
  font-size: 13px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

.demo-row:hover .demo-user {
  color: var(--accent);
}

.entry-note {
  margin: 26px 0 0;
  font-size: 15px;
  line-height: 1.75;
  color: var(--ink-3);
}

/* --------------------------------------------------- 窄屏：换成上下两段 */

@media (max-width: 980px) {
  .login {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
    height: auto;
    min-height: 100%;
  }

  .book {
    padding: 22px 22px 20px;
  }

  .book-main {
    margin: 0;
    padding: 26px 0 22px;
    max-width: none;
  }

  .book-title {
    font-size: 26px;
    line-height: 1.3;
  }

  .book-lead {
    margin-top: 14px;
    font-size: 13.5px;
    line-height: 1.7;
  }

  .rules {
    display: none;
  }

  .entry {
    align-items: flex-start;
    padding: 28px 22px 56px;
  }

  .entry-title,
  .entry-sub {
    display: none;
  }

  .demo {
    margin-top: 26px;
  }
}
</style>
