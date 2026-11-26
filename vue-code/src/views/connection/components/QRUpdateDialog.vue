<script setup lang="ts">
import { ref, watch } from 'vue'
import { generateQRCode, getQRCodeStatus, getQRCodeCookies } from '@/api/qrlogin'
import { updateCookie } from '@/api/websocket'
import { showSuccess, showError } from '@/utils'
import type { QRLoginSession } from '@/types'

interface Props {
  modelValue: boolean
  accountId: number
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
  (e: 'need-captcha'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const qrCodeUrl = ref('')
const sessionId = ref('')
const status = ref<QRLoginSession['status']>('pending')
const statusText = ref('正在生成二维码...')
const verificationUrl = ref('')
let pollTimer: number | null = null
let handlingSuccess = false

watch(() => props.modelValue, (newVal) => {
  if (newVal) {
    handlingSuccess = false
    verificationUrl.value = ''
    status.value = 'pending'
    statusText.value = '正在生成二维码...'
    generateQR()
  } else {
    stopPolling()
    handlingSuccess = false
  }
})

const generateQR = async () => {
  stopPolling()
  handlingSuccess = false
  verificationUrl.value = ''
  qrCodeUrl.value = ''
  status.value = 'pending'
  statusText.value = '正在生成二维码...'
  try {
    const response = await generateQRCode()
    if (response.code === 0 || response.code === 200) {
      qrCodeUrl.value = response.data?.qrCodeUrl || ''
      sessionId.value = response.data?.sessionId || ''
      statusText.value = '等待扫码...'
      startPolling()
    } else {
      throw new Error(response.msg || '生成二维码失败')
    }
  } catch (error: any) {
    console.error('生成二维码失败:', error)
    statusText.value = '生成二维码失败，请重试'
    showError('生成二维码失败')
  }
}

const startPolling = () => {
  if (!sessionId.value) {
    showError('会话ID为空，无法查询状态')
    return
  }
  stopPolling()
  pollTimer = window.setInterval(async () => {
    if (!sessionId.value || handlingSuccess) return
    try {
      const response = await getQRCodeStatus(sessionId.value)
      if (response.code === 0 || response.code === 200) {
        const data = response.data
        status.value = data?.status || 'pending'

        switch (data?.status) {
          case 'pending':
            statusText.value = '等待扫码...'
            break
          case 'scanned':
            statusText.value = '已扫码，请在手机端确认...'
            break
          case 'confirmed':
            // 立即停止轮询并加防重入锁，防止2秒定时器并发重复执行更新与重连
            stopPolling()
            if (handlingSuccess) return
            handlingSuccess = true
            statusText.value = '扫码确认成功！正在更新凭证并连接...'
            await handleLoginSuccess()
            break
          case 'verification_required':
            stopPolling()
            verificationUrl.value = data?.verificationUrl || ''
            statusText.value = '账号触发安全验证，请点击下方完成验证或改用网页版手动更新Cookie'
            showError('扫码触发安全验证，请完成验证或使用网页版手动更新Cookie')
            break
          case 'cancelled':
            stopPolling()
            statusText.value = '已取消扫码登录，可重新生成二维码'
            break
          case 'expired':
          case 'not_found':
            stopPolling()
            statusText.value = '二维码已过期，请点击下方重新获取'
            break
          case 'error':
            stopPolling()
            statusText.value = data?.message || '提取登录凭证失败，请重新扫码'
            showError(statusText.value)
            break
        }
      }
    } catch (error) {
      console.error('检查登录状态失败:', error)
    }
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const openVerificationPage = () => {
  if (verificationUrl.value) {
    window.open(verificationUrl.value, '_blank')
  }
}

const handleLoginSuccess = async () => {
  try {
    // 1. 获取完整Cookie
    const cookieRes = await getQRCodeCookies(sessionId.value)
    if (cookieRes.code !== 0 && cookieRes.code !== 200) {
      showError(cookieRes.msg || '获取Cookie失败')
      handleClose()
      return
    }

    const cookieText = cookieRes.data?.cookies || ''
    if (!cookieText) {
      showError('扫码返回的Cookie为空，请重新扫码')
      handleClose()
      return
    }

    // 2. 更新Cookie（后端会自动清理旧Token/滑块锁并启动校验WebSocket连接）
    statusText.value = '正在保存新Cookie并建立WebSocket长连接...'
    const cookieUpdateRes = await updateCookie({
      xianyuAccountId: props.accountId,
      cookieText: cookieText
    })

    if (cookieUpdateRes.code !== 0 && cookieUpdateRes.code !== 200) {
      showError(cookieUpdateRes.msg || '更新Cookie失败')
      handleClose()
      return
    }

    const resData = cookieUpdateRes.data as any
    emit('success')
    handleClose()

    if (resData?.wsConnected === true) {
      showSuccess('扫码更新成功，WebSocket已稳定连接')
    } else if (resData?.needCaptcha === true) {
      showError(resData.message || 'Cookie已更新，但触发闲鱼滑块验证，请按指引过滑块')
      emit('need-captcha')
    } else {
      showError(resData?.message || 'Cookie已更新，但WebSocket连接未成功，请查看操作日志')
    }

  } catch (error: any) {
    console.error('扫码更新失败:', error)
    if (!error?.messageShown) {
      showError(error.message || '扫码更新失败')
    }
    handleClose()
  }
}

const handleClose = () => {
  stopPolling()
  emit('update:modelValue', false)
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
          <div class="modal-header">
            <h2 class="modal-title">扫码更新 Cookie 和 Token</h2>
            <button class="modal-close" @click="handleClose">×</button>
          </div>
          <div class="modal-body">
            <div class="qr-code-wrap">
              <img v-if="qrCodeUrl" :src="qrCodeUrl" alt="二维码" class="qr-code" />
              <div v-else class="qr-loading"><div class="loading-spinner"></div></div>
            </div>
            <p class="qr-tip">请使用闲鱼APP扫描二维码完成更新</p>
            <div class="qr-status">
              <span class="status-tag" :class="status === 'confirmed' ? 'is-success' : ''">{{ statusText }}</span>
            </div>
            <p v-if="sessionId" class="session-id">会话ID: {{ sessionId }}</p>
          </div>
          <div class="modal-footer">
            <button v-if="verificationUrl" class="btn btn-primary" @click="openVerificationPage">前往安全验证</button>
            <button v-if="status === 'expired' || status === 'cancelled' || status === 'error' || status === 'not_found'" class="btn btn-primary" @click="generateQR">重新获取二维码</button>
            <button class="btn btn-secondary" @click="handleClose">取消</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.20);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 24px;
}

.modal-container {
  background: rgba(255,255,255,0.72);
  border-radius: 20px;
  width: 100%;
  max-width: 360px;
  box-shadow: 0 32px 100px rgba(0, 0, 0, 0.14), 0 12px 32px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  flex-shrink: 0;
}

.modal-title {
  font-size: 15px;
  font-weight: 600;
  color: #1c1c1e;
  margin: 0;
}

.modal-close {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  border: none;
  background: transparent;
  color: rgba(28,28,30,.55);
  font-size: 18px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.modal-close:hover {
  background: rgba(60,60,67,.12);
  color: #1c1c1e;
}

.modal-body {
  padding: 0 20px 20px;
  text-align: center;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
}

.btn {
  padding: 8px 18px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  border: none;
}

.btn-secondary {
  background: rgba(60,60,67,.12);
  color: #1c1c1e;
}

.btn-secondary:hover {
  background: rgba(0, 0, 0, 0.1);
}

.qr-code-wrap {
  margin: 16px 0;
  display: flex;
  justify-content: center;
}

.qr-code {
  max-width: 180px;
  border-radius: 12px;
}

.qr-loading {
  width: 180px;
  height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f7;
  border-radius: 12px;
}

.loading-spinner {
  width: 28px;
  height: 28px;
  border: 2px solid #e5e5e5;
  border-top-color: #0071e3;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.qr-tip {
  margin: 8px 0;
  color: #1c1c1e;
  font-size: 14px;
}

.qr-status {
  margin: 10px 0;
  min-height: 28px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.status-tag {
  padding: 5px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  background: rgba(255,255,255,0.38);
  color: rgba(28,28,30,.55);
}

.status-tag.is-success {
  background: rgba(52, 199, 89, 0.1);
  color: #30D158;
}

.session-id {
  margin: 8px 0;
  font-size: 11px;
  color: rgba(28,28,30,.55);
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s cubic-bezier(0.32, 0.94, 0.6, 1), opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}
</style>
