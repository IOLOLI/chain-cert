import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

/**
 * 路由与角色门禁。
 *
 * 这里的 roles 只是"链下第一道闸门"（决定菜单与页面可见性）；
 * 最终裁决在合约：链下角色写得再对，合约不认照样回滚。
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('../layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '首页概览', icon: 'HomeFilled', group: '账本' }
      },

      // ---------------- ADMIN ----------------
      {
        path: 'admin/institutions',
        name: 'admin-institutions',
        component: () => import('../views/admin/Institutions.vue'),
        meta: { title: '机构管理', icon: 'OfficeBuilding', group: '机构与人员', roles: ['ADMIN'] }
      },
      {
        path: 'admin/persons',
        name: 'admin-persons',
        component: () => import('../views/admin/Persons.vue'),
        meta: { title: '职工建档', icon: 'UserFilled', group: '机构与人员', roles: ['ADMIN'] }
      },
      {
        path: 'admin/policy',
        name: 'admin-policy',
        component: () => import('../views/admin/Policy.vue'),
        meta: { title: '政策参数', icon: 'SetUp', group: '机构与人员', roles: ['ADMIN'] }
      },

      // ---------------- SPONSOR ----------------
      {
        path: 'sponsor/accounts',
        name: 'sponsor-accounts',
        component: () => import('../views/sponsor/Accounts.vue'),
        meta: { title: '职工账号', icon: 'UserFilled', group: '缴费单位', roles: ['SPONSOR'] }
      },
      {
        path: 'sponsor/employees',
        name: 'sponsor-employees',
        component: () => import('../views/sponsor/Employees.vue'),
        meta: { title: '职工管理', icon: 'User', group: '缴费单位', roles: ['SPONSOR'] }
      },
      {
        path: 'sponsor/payments',
        name: 'sponsor-payments',
        component: () => import('../views/sponsor/Payments.vue'),
        meta: { title: '缴费管理', icon: 'Money', group: '缴费单位', roles: ['SPONSOR'] }
      },

      // ---------------- EMPLOYEE ----------------
      {
        path: 'employee/account',
        name: 'employee-account',
        component: () => import('../views/employee/Account.vue'),
        meta: { title: '我的社保账户', icon: 'Tickets', group: '我的参保', roles: ['EMPLOYEE'] }
      },
      {
        path: 'employee/transfer',
        name: 'employee-transfer',
        component: () => import('../views/employee/Transfer.vue'),
        meta: { title: '关系转移', icon: 'Switch', group: '我的参保', roles: ['EMPLOYEE'] }
      },

      // ---------------- BUREAU ----------------
      {
        path: 'bureau/accounts',
        name: 'bureau-accounts',
        component: () => import('../views/bureau/Accounts.vue'),
        meta: { title: '缴费单位账号', icon: 'OfficeBuilding', group: '社保局经办', roles: ['BUREAU'] }
      },
      {
        path: 'bureau/region',
        name: 'bureau-region',
        component: () => import('../views/bureau/Region.vue'),
        meta: { title: '辖区总览', icon: 'DataLine', group: '社保局经办', roles: ['BUREAU'] }
      },
      {
        path: 'bureau/pension',
        name: 'bureau-pension',
        component: () => import('../views/bureau/Pension.vue'),
        meta: { title: '养老金支出', icon: 'Money', group: '社保局经办', roles: ['BUREAU'] }
      },
      {
        path: 'bureau/inbox',
        name: 'bureau-inbox',
        component: () => import('../views/bureau/Inbox.vue'),
        meta: { title: '转移办理', icon: 'Files', group: '社保局经办', roles: ['BUREAU'] }
      },

      // ---------------- REGULATOR ----------------
      {
        path: 'regulator/overview',
        name: 'regulator-overview',
        component: () => import('../views/regulator/Overview.vue'),
        meta: { title: '监管大屏', icon: 'DataAnalysis', group: '监督', roles: ['REGULATOR', 'ADMIN'] }
      },

      // ---------------- 通用 ----------------
      {
          path: 'chain/explorer',
          name: 'chain-explorer',
          component: () => import('../views/common/Explorer.vue'),
          meta: { title: '区块链浏览器', icon: 'Connection', group: '链上工具' }

        },

      {
          path: 'chain/index',
        name: 'chain-index',
        component: () => import('../views/common/IndexData.vue'),
        // 链上索引只对 ADMIN / REGULATOR 开放（与下面的合约调试台各自独立，勿一起改）
        meta: { title: '链上索引', icon: 'List', group: '链上工具', roles: ['ADMIN', 'REGULATOR'] }
      },
      {
        path: 'chain/console',
        name: 'chain-console',
        component: () => import('../views/common/Console.vue'),
        meta: {
          title: '合约调试台', group: '链上工具',
          icon: 'Cpu',
          roles: ['ADMIN']
        }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(async (to) => {
  const user = useUserStore()

  if (to.meta.public) {
    return true
  }
  if (!user.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (!user.profile) {
    try {
      await user.loadProfile()
    } catch (e) {
      user.logout()
      return { path: '/login' }
    }
  }
  const roles = to.meta.roles
  if (roles && roles.length && !roles.includes(user.role)) {
    ElMessage.warning(`当前角色「${user.role}」无权访问该页面`)
    return { path: '/dashboard' }
  }
  return true
})

export default router
