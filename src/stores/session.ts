import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi, type SessionUserResponse } from '@/api/careerlens'
import {
  ACCESS_TOKEN_KEY,
  REMEMBERED_TOKEN_KEY,
  UNAUTHORIZED_EVENT,
} from '@/api/http'

const USER_KEY = 'careerlens-session-user'
const REMEMBERED_USER_KEY = 'careerlens-remembered-user'

function readStoredUser(): SessionUserResponse | null {
  const value = sessionStorage.getItem(USER_KEY) ?? localStorage.getItem(REMEMBERED_USER_KEY)
  if (!value) return null
  try {
    return JSON.parse(value) as SessionUserResponse
  } catch {
    sessionStorage.removeItem(USER_KEY)
    return null
  }
}

export const useSessionStore = defineStore('session', () => {
  const rememberedToken = localStorage.getItem(REMEMBERED_TOKEN_KEY) ?? ''
  const initialToken = sessionStorage.getItem(ACCESS_TOKEN_KEY) ?? rememberedToken
  if (initialToken && !sessionStorage.getItem(ACCESS_TOKEN_KEY))
    sessionStorage.setItem(ACCESS_TOKEN_KEY, initialToken)
  const rememberedUser = localStorage.getItem(REMEMBERED_USER_KEY)
  if (rememberedUser && !sessionStorage.getItem(USER_KEY))
    sessionStorage.setItem(USER_KEY, rememberedUser)
  const accessToken = ref(initialToken)
  const user = ref<SessionUserResponse | null>(readStoredUser())
  const loading = ref(false)
  const initialized = ref(false)
  const error = ref('')
  const remoteMode = computed(() => true)
  const authenticated = computed(() => Boolean(accessToken.value))
  let unauthorizedBound = false

  function persistSession(token: string, sessionUser: SessionUserResponse, remember = false) {
    accessToken.value = token
    user.value = sessionUser
    sessionStorage.setItem(ACCESS_TOKEN_KEY, token)
    sessionStorage.setItem(USER_KEY, JSON.stringify(sessionUser))
    if (remember) {
      localStorage.setItem(REMEMBERED_TOKEN_KEY, token)
      localStorage.setItem(REMEMBERED_USER_KEY, JSON.stringify(sessionUser))
    } else {
      localStorage.removeItem(REMEMBERED_TOKEN_KEY)
      localStorage.removeItem(REMEMBERED_USER_KEY)
    }
  }

  function clearSession() {
    accessToken.value = ''
    user.value = null
    sessionStorage.removeItem(ACCESS_TOKEN_KEY)
    sessionStorage.removeItem(USER_KEY)
    localStorage.removeItem(REMEMBERED_TOKEN_KEY)
    localStorage.removeItem(REMEMBERED_USER_KEY)
  }

  function bindUnauthorizedHandler() {
    if (unauthorizedBound) return
    window.addEventListener(UNAUTHORIZED_EVENT, clearSession)
    unauthorizedBound = true
  }

  async function initialize() {
    bindUnauthorizedHandler()
    if (initialized.value) return authenticated.value
    if (!accessToken.value) {
      initialized.value = true
      return authenticated.value
    }
    loading.value = true
    try {
      const currentUser = await authApi.me()
      user.value = currentUser
      sessionStorage.setItem(USER_KEY, JSON.stringify(currentUser))
      if (localStorage.getItem(REMEMBERED_TOKEN_KEY) === accessToken.value)
        localStorage.setItem(REMEMBERED_USER_KEY, JSON.stringify(currentUser))
      return true
    } catch {
      clearSession()
      return false
    } finally {
      loading.value = false
      initialized.value = true
    }
  }

  async function login(email: string, password: string, autoLogin = false) {
    loading.value = true
    error.value = ''
    try {
      const response = await authApi.login({ email: email.trim(), password, rememberMe: autoLogin })
      persistSession(response.accessToken, response.user, autoLogin)
      initialized.value = true
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '登录失败，请稍后重试'
      return false
    } finally {
      loading.value = false
    }
  }

  async function register(email: string, password: string, displayName: string, autoLogin = false) {
    loading.value = true
    error.value = ''
    try {
      const response = await authApi.register({ email: email.trim(), password, displayName: displayName.trim() })
      persistSession(response.accessToken, response.user, autoLogin)
      initialized.value = true
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '注册失败，请稍后重试'
      return false
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    const callRemote = remoteMode.value && Boolean(accessToken.value)
    try {
      if (callRemote) await authApi.logout()
    } catch {
      // Local logout still completes if the service is temporarily unavailable.
    } finally {
      clearSession()
      initialized.value = true
    }
  }

  return {
    accessToken,
    user,
    loading,
    initialized,
    error,
    remoteMode,
    authenticated,
    initialize,
    login,
    register,
    logout,
  }
})
