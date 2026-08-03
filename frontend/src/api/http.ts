import axios from 'axios'
import type { ApiResult } from '../types/domain'

const TOKEN_KEY = 'ai_tutor_token'

export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  timeout: 120000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const result = response.data as ApiResult<unknown>
    if (result.code === 200) {
      return response
    }

    if (result.code === 401) {
      localStorage.removeItem(TOKEN_KEY)
    }

    throw new ApiError(result.code, result.message || '请求失败')
  },
  (error) => {
    if (error instanceof ApiError) {
      throw error
    }
    throw new ApiError(500, error?.message || '网络请求失败')
  }
)

export async function request<T>(config: Parameters<typeof http.request>[0]) {
  const response = await http.request<ApiResult<T>>(config)
  return response.data.data
}

export { TOKEN_KEY }
