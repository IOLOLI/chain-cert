import { defineStore } from 'pinia'
import { authApi } from '../api'
import { TOKEN_KEY } from '../api/http'

/**
 * 统一把 login / me 的返回映射成 store 里的 profile：补全 chainAddress 字段。
 * 后端尚未返回 chainAddress 时存空串，由 chainAddress getter 回退到钱包主地址。
 */
function mapProfile(data) {
  if (!data || typeof data !== 'object') return data
  return { ...data, chainAddress: data.chainAddress || '' }
}

/**
 * 当前登录用户。
 *
 * 注意：这里的 role 是"链下登记角色"，只用于界面与菜单；
 * 真正的权限在合约里（按交易签名地址判定），链下角色与链上不一致时交易会被合约回滚。
 * 页面上的"链上角色"（profile.onChainRole / wallet.onChainRole）来自合约 roleOf，可用于对照。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    profile: null
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    role: (state) => (state.profile && state.profile.role) || '',
    displayName: (state) =>
      (state.profile && (state.profile.displayName || state.profile.username)) || '',
    institutionCode: (state) => (state.profile && state.profile.institutionCode) || '',
    wallets: (state) => (state.profile && state.profile.wallets) || [],
    /**
     * 链上角色：后端把 roleOf 的结果放在**每个钱包项**上（wallets[].onChainRole），
     * 顶层 profile.onChainRole 只是兼容字段。取值顺序：
     *   顶层 profile.onChainRole → 主钱包（primaryWallet === true）→ 第一个钱包 → 空串。
     * 职工账号等在链上没有角色，此时返回空串是正确结果，页面据此显示"未查询到"，不要造假值。
     */
    onChainRole(state) {
      const profile = state.profile
      if (!profile) return ''
      if (profile.onChainRole) return profile.onChainRole
      const list = Array.isArray(profile.wallets) ? profile.wallets : []
      if (!list.length) return ''
      const wallet = list.find((w) => w && w.primaryWallet) || list[0]
      return (wallet && wallet.onChainRole) || ''
    },
    /** 已验签的主地址：职工身份、SPONSOR 地址等都用它 */
    primaryAddress(state) {
      const list = this.wallets.filter((w) => w.verified)
      if (list.length) {
        return (list.find((w) => w.primaryWallet) || list[0]).address
      }
      return ''
    },
    /**
     * 账号自己的链上地址：机构账号是机构密钥地址、职工账号是创建时自动生成的地址。
     * 优先用后端返回的 chainAddress，旧账号 / 后端未就绪时回退到已验签的钱包主地址。
     */
    chainAddress(state) {
      const fromProfile = state.profile && state.profile.chainAddress
      return fromProfile || this.primaryAddress || ''
    },
    isAdmin: (state) => state.profile && state.profile.role === 'ADMIN',
    isSponsor: (state) => state.profile && state.profile.role === 'SPONSOR',
    isBureau: (state) => state.profile && state.profile.role === 'BUREAU',
    isRegulator: (state) => state.profile && state.profile.role === 'REGULATOR',
    isEmployee: (state) => state.profile && state.profile.role === 'EMPLOYEE'
  },

  actions: {
    async login(payload) {
      const data = await authApi.login(payload)
      this.token = data.token
      localStorage.setItem(TOKEN_KEY, data.token)
      this.profile = mapProfile(data.account)
      return data
    },

    async loadProfile() {
      this.profile = mapProfile(await authApi.me())
      return this.profile
    },

    /**
     * 用后端返回值局部更新本地资料（例如「我的信息」里改完用户名 / 显示名后刷新界面），
     * 不必重新登录，也不覆盖其它字段。
     */
    setProfile(patch) {
      if (!this.profile || !patch) return
      this.profile = { ...this.profile, ...patch }
    },

    /**
     * 取"本账号的链上地址"前调用：本地没有地址时向 /auth/me 补一次资料。
     * 后端未就绪 / 地址确实为空时也不抛错，由页面自己决定怎么提示。
     */
    async ensureChainAddress() {
      if (this.chainAddress) return this.chainAddress
      try {
        await this.loadProfile()
      } catch (e) {
        /* 忽略：拿不到地址时让页面走"请联系管理员"的提示 */
      }
      return this.chainAddress
    },

    logout() {
      this.token = ''
      this.profile = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})
