import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

/**
 * 统一 HTTP 客户端
 *
 * 后端约定：所有接口返回 { code, message, data, timestamp }
 *  - code === 0 表示成功
 *  - 认证失败 HTTP 401，权限不足 HTTP 403，链未就绪 HTTP 503（code 2001）
 * 这里把 data 直接返回给业务层，错误统一弹提示。
 *
 * 例外：请求配置传 { silent: true } 时只 reject、不弹全局提示。
 * 用于「调用方自己已经降级处理」的可预期失败——例如首页探测旧合约有没有
 * totalPensionPaid() 这个 getter，失败就该按 0 显示，而不是糊用户一脸红条。
 * 401 不受 silent 影响：登录失效必须跳转。
 */
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 30000
})

export const TOKEN_KEY = 'chainsip_token'

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code !== 'undefined') {
      if (body.code === 0) {
        return body.data
      }
      const silent = response.config && response.config.silent
      if (!silent) {
        ElMessage.error(body.message || '请求失败')
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    const config = error.config || {}
    const status = error.response && error.response.status
    const body = error.response && error.response.data
    const message = (body && body.message) || error.message || '网络异常'

    if (status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      ElMessage.error(message || '登录已失效，请重新登录')
      if (router.currentRoute.value.path !== '/login') {
        router.push('/login')
      }
    } else if (config.silent) {
      // 调用方自己降级：不弹提示，只把错误抛回去
    } else if (status === 403) {
      ElMessage.error(message)
    } else if (status === 503) {
      // 链未就绪：把后端给的具体排查提示原样展示，便于现场定位
      ElMessage.warning(message)
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

export default http
