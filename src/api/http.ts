export interface ApiEnvelope<T> {
  data: T
}

export interface ApiProblem {
  title?: string
  detail?: string
  status?: number
  code?: string
}

export const ACCESS_TOKEN_KEY = 'careerlens-access-token'
export const REMEMBERED_TOKEN_KEY = 'careerlens-remembered-access-token'
export const UNAUTHORIZED_EVENT = 'careerlens:unauthorized'

export class ApiError extends Error {
  readonly status: number
  readonly code?: string

  constructor(message: string, status: number, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

const configuredBaseUrl = String(import.meta.env.VITE_API_BASE_URL ?? '').trim()
export const API_BASE_URL = (configuredBaseUrl || 'http://127.0.0.1:8080/api/v1').replace(/\/$/, '')

function storedToken(): string {
  if (typeof window === 'undefined') return ''
  return sessionStorage.getItem(ACCESS_TOKEN_KEY)
    ?? localStorage.getItem(REMEMBERED_TOKEN_KEY)
    ?? ''
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body !== undefined && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  const token = storedToken()
  if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers })
  } catch (cause) {
    throw new ApiError(
      cause instanceof Error ? `服务连接失败：${cause.message}` : '服务连接失败，请检查后端是否已启动',
      0,
      'NETWORK_ERROR',
    )
  }

  if (token && storedToken() !== token) throw new ApiError('账号会话已变化，旧请求已丢弃', 409, 'SESSION_CHANGED')
  if (!response.ok) {
    const problem = await response.json().catch(() => ({})) as ApiProblem
    const error = new ApiError(
      problem.detail ?? problem.title ?? `请求失败（${response.status}）`,
      response.status,
      problem.code ?? problem.title,
    )
    if (response.status === 401 && !path.startsWith('/auth/login') && !path.startsWith('/auth/register')) {
      sessionStorage.removeItem(ACCESS_TOKEN_KEY)
      localStorage.removeItem(REMEMBERED_TOKEN_KEY)
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
    }
    throw error
  }

  if (response.status === 204) return undefined as T
  const payload = await response.json() as ApiEnvelope<T> | T
  return typeof payload === 'object' && payload !== null && 'data' in payload
    ? (payload as ApiEnvelope<T>).data
    : payload as T
}

export const http = {
  upload: <T>(path: string, file: File) => { const body=new FormData(); body.append('file',file); return request<T>(path,{method:'POST',body}) },
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => request<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
  }),
  put: <T>(path: string, body?: unknown) => request<T>(path, {
    method: 'PUT',
    body: body === undefined ? undefined : JSON.stringify(body),
  }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, {
    method: 'PATCH',
    body: body === undefined ? undefined : JSON.stringify(body),
  }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

export function isApiConfigured(): boolean {
  return Boolean(API_BASE_URL)
}

export function shouldUseRemoteApi(): boolean {
  return true
}

export function hasStoredAccessToken(): boolean {
  return Boolean(storedToken())
}
