<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Check, KeyRound, LoaderCircle, LockKeyhole, ShieldCheck, Sparkles } from '@lucide/vue'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const SAVED_EMAIL_KEY = 'careerlens-saved-email'
const SAVE_PASSWORD_KEY = 'careerlens-save-password'
const email = ref(localStorage.getItem(SAVED_EMAIL_KEY) ?? '')
const password = ref('')
const displayName = ref('')
const mode = ref<'login' | 'register'>('login')
const savePassword = ref(localStorage.getItem(SAVE_PASSWORD_KEY) === 'true')
const autoLogin = ref(localStorage.getItem('careerlens-auto-login') === 'true')
const redirectTarget = computed(() => {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  return redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/'
})

async function submit() {
  const success = mode.value === 'login'
    ? await session.login(email.value, password.value, autoLogin.value)
    : await session.register(email.value, password.value, displayName.value, autoLogin.value)
  localStorage.setItem('careerlens-auto-login', String(autoLogin.value))
  localStorage.setItem(SAVE_PASSWORD_KEY, String(savePassword.value))
  if (success && (savePassword.value || autoLogin.value)) localStorage.setItem(SAVED_EMAIL_KEY, email.value.trim())
  else if (success) localStorage.removeItem(SAVED_EMAIL_KEY)
  if (success && savePassword.value) await saveBrowserCredential(email.value.trim(), password.value)
  if (success) await router.replace(redirectTarget.value)
}

async function restoreBrowserCredential() {
  if (!savePassword.value || !navigator.credentials?.get) return
  try {
    const credential = await navigator.credentials.get({ password: true, mediation: 'optional' } as CredentialRequestOptions) as (Credential & { id?: string; password?: string }) | null
    if (credential?.id) email.value = credential.id
    if (credential?.password) password.value = credential.password
  } catch {
    // The browser may require the user to select a saved credential manually.
  }
}

onMounted(restoreBrowserCredential)

async function saveBrowserCredential(identifier: string, secret: string) {
  const Credential = (globalThis as typeof globalThis & { PasswordCredential?: new (data: { id: string; password: string }) => Credential }).PasswordCredential
  if (!Credential || !navigator.credentials?.store) {
    session.error = '当前浏览器不支持安全保存密码，请使用浏览器自带密码管理器。'
    return
  }
  try {
    await navigator.credentials.store(new Credential({ id: identifier, password: secret }))
  } catch {
    session.error = '浏览器拒绝保存密码，请检查浏览器密码管理设置。'
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-story">
      <RouterLink to="/" class="login-brand" aria-label="职镜首页">
        <span class="brand-mark" aria-hidden="true"><span class="brand-mark__lens"></span><span class="brand-mark__point"></span></span>
        <span><strong>职镜</strong><small>CareerLens</small></span>
      </RouterLink>
      <div class="login-story__content">
        <span class="login-kicker"><Sparkles :size="15" /> 让每次投递都有证据</span>
        <h1>从简历优化，到机会发现与投递执行。</h1>
        <p>把岗位要求、简历证据和投递进度放在一个清晰、可追溯的工作区里。</p>
        <ul>
          <li><Check :size="15" /> 简历版本和岗位匹配结果完整保存</li>
          <li><Check :size="15" /> BOSS 与猎聘任务发送前逐项确认</li>
          <li><Check :size="15" /> 浏览器会话和验证码始终留在本机</li>
        </ul>
      </div>
      <div class="login-trust"><ShieldCheck :size="17" /><span><strong>隐私优先</strong><small>只同步执行所需的最少数据</small></span></div>
    </section>

    <section class="login-panel">
      <div class="login-card">
        <header>
          <span class="login-card__icon"><LockKeyhole :size="22" /></span>
          <div><p class="eyebrow">{{ mode === 'login' ? '欢迎回来' : '开始使用' }}</p><h2>{{ mode === 'login' ? '登录求职工作区' : '创建你的工作区' }}</h2></div>
        </header>

        <form class="login-form" @submit.prevent="submit">
          <label v-if="mode === 'register'" class="field">
            <span>姓名</span>
            <input v-model="displayName" autocomplete="name" placeholder="你的姓名" required />
          </label>
          <label class="field">
            <span>邮箱</span>
            <span class="input-with-icon"><span class="login-input-symbol">@</span><input v-model="email" autocomplete="email" type="email" placeholder="name@example.com" required /></span>
          </label>
          <label class="field">
            <span>密码</span>
            <span class="input-with-icon"><KeyRound :size="16" /><input v-model="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" type="password" placeholder="至少 8 位，包含字母和数字" minlength="8" required /></span>
          </label>
          <div class="login-options" role="group" aria-label="登录选项">
            <label class="login-option"><input v-model="savePassword" type="checkbox" /><span class="login-option__switch"></span><span>保存密码</span></label>
            <label class="login-option"><input v-model="autoLogin" type="checkbox" /><span class="login-option__switch"></span><span>自动登录</span></label>
          </div>
          <p v-if="session.error" class="login-error" role="alert">{{ session.error }}</p>
          <button class="button button--primary login-submit" type="submit" :disabled="session.loading">
            <LoaderCircle v-if="session.loading" class="login-spinner" :size="17" />
            <span>{{ session.loading ? '正在处理…' : mode === 'login' ? '登录' : '注册并进入' }}</span><ArrowRight v-if="!session.loading" :size="16" />
          </button>
        </form>

        <div class="login-divider"><span>{{ mode === 'login' ? '还没有账号' : '已有账号' }}</span></div>
        <button class="button button--secondary login-mode-button" type="button" :disabled="session.loading" @click="mode = mode === 'login' ? 'register' : 'login'; session.error = ''">
          <Sparkles :size="16" /> {{ mode === 'login' ? '创建新账号' : '返回登录' }}
        </button>

        <footer><ShieldCheck :size="14" /> 密码由浏览器管理；自动登录令牌可随时退出或撤销</footer>
      </div>
    </section>
  </main>
</template>
