import { request } from '@/utils/request'

/** 检查用户是否存在 */
export function checkUserExists() {
  return request<{ exists: boolean }>({
    url: '/login/checkUserExists',
    method: 'post'
  })
}

/** 图形验证码：{ id, image(data URI) } */
export function getCaptcha() {
  return request<{ id: string; image: string }>({
    url: '/login/captcha',
    method: 'get'
  })
}

/** 登录 */
export function login(data: { username: string; password: string; captchaId?: string; captchaCode?: string }) {
  return request<{ token: string; username: string }>({
    url: '/login/login',
    method: 'post',
    data
  })
}

/** 注册 */
export function register(data: { username: string; password: string; confirmPassword: string }) {
  return request<{ token: string; username: string }>({
    url: '/login/register',
    method: 'post',
    data
  })
}

/** 退出登录 */
export function logout() {
  return request<null>({
    url: '/login/logout',
    method: 'post'
  })
}
