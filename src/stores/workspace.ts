import { computed, reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import { careerLensApi, type ApplicationResponse, type ResumeSummaryResponse, type ResumeVersionResponse } from '@/api/careerlens'
import { normalizeResume, newSection, type ResumeSection } from '@/domain/resume'
import type { ApplicationStage, JobApplication, ToastItem } from '@/types'

interface ResumeForm {
  name: string; headline: string; email: string; phone: string; wechat: string; location: string; website: string; summary: string; photoDataUrl: string
  projectTitle: string; projectPeriod: string; projectRole: string; projectHighlights: string[]
  educationSchool: string; educationMajor: string; educationPeriod: string
}
const emptyResume = (): ResumeForm => ({
  name: '', headline: '', email: '', phone: '', wechat: '', location: '', website: '', summary: '', photoDataUrl: '',
  projectTitle: '', projectPeriod: '', projectRole: '', projectHighlights: [],
  educationSchool: '', educationMajor: '', educationPeriod: '',
})
function record(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : {}
}
function text(value: unknown): string { return typeof value === 'string' ? value : '' }
function applicationView(item: ApplicationResponse): JobApplication {
  return { ...item, updatedAt: new Date(item.updatedAt).toLocaleString('zh-CN', { hour12: false }) }
}

export const useWorkspaceStore = defineStore('workspace', () => {
  let originalContent: Record<string, unknown> = {}
  let loadPromise: Promise<void> | undefined
  let saveQueue: Promise<void> = Promise.resolve()
  const theme = ref<'light' | 'dark'>('light')
  const initialized = ref(false)
  const loading = ref(false)
  const error = ref('')
  const resumeVersion = ref(0)
  const activeResumeId = ref('')
  const activeResumeVersionId = ref('')
  const draftRevision = ref(0)
  const resumes = ref<ResumeSummaryResponse[]>([])
  const resumeVersions = ref<ResumeVersionResponse[]>([])
  const resume = reactive<ResumeForm>(emptyResume())
  const dynamicSections = ref<ResumeSection[]>([])
  const skills = ref<string[]>([])
  const importPending = ref(false)
  const sourceDocument = ref<Record<string, unknown>>({})
  const resumeLoading = ref(false)
  const saveConflict = ref('')
  const applications = ref<JobApplication[]>([])
  const toasts = ref<ToastItem[]>([])
  let toastId = 0

  const activeResumeSummary = computed(() => resumes.value.find((item) => item.id === activeResumeId.value) ?? null)
  const completionRate = computed(() => activeResumeSummary.value?.completionRate ?? 0)

  function addToast(title: string, message = '', tone: ToastItem['tone'] = 'success') {
    const id = ++toastId
    toasts.value.push({ id, title, message, tone })
    window.setTimeout(() => removeToast(id), 3800)
  }
  function removeToast(id: number) { toasts.value = toasts.value.filter((toast) => toast.id !== id) }

  function readResumeContent(contentValue: unknown) {
    const content=normalizeResume(contentValue)
    originalContent=JSON.parse(JSON.stringify(content))
    importPending.value=content.importReviewPending===true
    sourceDocument.value=record(content.sourceDocument)
    Object.assign(resume,emptyResume(),content.profile)
    dynamicSections.value=content.sections.length ? content.sections : ['PROJECT','EDUCATION','SKILLS'].map(type=>newSection(type))
    skills.value=[]
  }

  function serializeResume(): Record<string, unknown> {
    return {
      ...originalContent,schemaVersion:'2.0',importReviewPending:importPending.value,
      profile:{...record(originalContent.profile),name:resume.name,headline:resume.headline,email:resume.email,
        phone:resume.phone,wechat:resume.wechat,location:resume.location,website:resume.website,summary:resume.summary,
        photoDataUrl:resume.photoDataUrl},
      sections:JSON.parse(JSON.stringify(dynamicSections.value)),
    }
  }

  async function loadResume(id: string) {
    resumeLoading.value=true
    try {
    const detail = await careerLensApi.getResume(id)
    activeResumeId.value = id
    activeResumeVersionId.value = detail.resume.currentVersionId
    resumeVersion.value = detail.resume.currentVersion
    draftRevision.value = detail.draft.revision
    readResumeContent(detail.draft.content)
    const summaryIndex=resumes.value.findIndex(r=>r.id===id)
    if(summaryIndex>=0) resumes.value[summaryIndex]=detail.resume
    resumeVersions.value = await careerLensApi.listResumeVersions(id)
    saveConflict.value=''
    } finally {resumeLoading.value=false}
  }

  async function initialize(force = false) {
    if(loadPromise) return loadPromise
    loadPromise = loadState(force).finally(()=>{loadPromise=undefined})
    return loadPromise
  }
  async function loadState(force = false) {
    if (initialized.value && !force) return
    loading.value = true
    error.value = ''
    try {
      const [resumeList, applicationList] = await Promise.all([
        careerLensApi.listResumes(), careerLensApi.listApplications(),
      ])
      resumes.value = resumeList
      applications.value = applicationList.map(applicationView)
      const selected = resumeList.find((item) => item.id === activeResumeId.value) ?? resumeList[0]
      if (selected) await loadResume(selected.id)
      else {
        activeResumeId.value = ''; activeResumeVersionId.value = ''; resumeVersion.value = 0
        draftRevision.value = 0; resumeVersions.value = []; Object.assign(resume, emptyResume()); skills.value = []; dynamicSections.value=[]
      }
      initialized.value = true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '工作区加载失败'
      throw cause
    } finally { loading.value = false }
  }

  async function createResume(title = '未命名简历', content?: Record<string, unknown>) {
    const resumeContent = content ?? { schemaVersion: '1.0', profile: {}, sections: [] }
    const detail = await careerLensApi.createResume({ title, headline: text(record(resumeContent.profile).headline), content: resumeContent, source: 'MANUAL_PUBLISH' })
    resumes.value.unshift(detail.resume)
    await loadResume(detail.resume.id)
    addToast('简历已创建', title)
    return detail.resume
  }
  function saveDraft() {
    const next = saveQueue.catch(()=>{}).then(saveCurrentDraft)
    saveQueue = next
    return next
  }
  async function saveCurrentDraft() {
    if (!activeResumeId.value) {
      await createResume(resume.headline ? `${resume.headline}简历` : '未命名简历', serializeResume())
      return
    }
    const saved = await careerLensApi.saveResumeDraft(activeResumeId.value, draftRevision.value, serializeResume())
    draftRevision.value = saved.revision
    const summary = resumes.value.find((item) => item.id === activeResumeId.value)
    if (summary) summary.completionRate = saved.completionRate
  }
  async function publishResume() {
    if (!activeResumeId.value) await createResume('未命名简历', serializeResume())
    const version = await careerLensApi.publishResume(activeResumeId.value, serializeResume(), draftRevision.value)
    resumeVersion.value = version.version
    activeResumeVersionId.value = version.id
    const savedDraft = await careerLensApi.getResume(activeResumeId.value)
    draftRevision.value = savedDraft.draft.revision
    resumeVersions.value = await careerLensApi.listResumeVersions(activeResumeId.value)
    const summary = resumes.value.find((item) => item.id === activeResumeId.value)
    if (summary) { summary.currentVersion = version.version; summary.currentVersionId = version.id }
    addToast(`简历 v${version.version} 已发布`, '已生成不可变版本。')
  }
  async function reparseResume() {
    if (!activeResumeId.value) return
    const reparsed = await careerLensApi.reparseResume(activeResumeId.value, draftRevision.value)
    draftRevision.value = reparsed.revision
    readResumeContent(reparsed.content)
    const summary = resumes.value.find((item) => item.id === activeResumeId.value)
    if (summary) summary.completionRate = reparsed.completionRate
    saveConflict.value = ''
    addToast('重新识别完成', '请核对项目、教育和工作经历后再发布。')
  }
  async function duplicateResume(id: string) {
    const created = await careerLensApi.duplicateResume(id)
    resumes.value.unshift(created.resume)
    addToast('简历已复制', created.resume.title)
  }
  async function deleteResume(id: string) {
    await careerLensApi.deleteResume(id)
    resumes.value = resumes.value.filter((item) => item.id !== id)
    if (activeResumeId.value === id) {
      const next = resumes.value[0]
      if (next) await loadResume(next.id)
      else { activeResumeId.value = ''; activeResumeVersionId.value = ''; Object.assign(resume, emptyResume()); skills.value = [] }
    }
    addToast('简历已删除')
  }
  async function restoreResumeVersion(versionId: string) {
    if (!activeResumeId.value) return
    const restored = await careerLensApi.restoreResumeVersion(activeResumeId.value, versionId)
    await loadResume(activeResumeId.value)
    addToast(`已恢复为新版本 v${restored.version}`, '历史版本保持不变。')
  }


  function addSkill(skill: string) {
    const normalized = skill.trim()
    if (normalized && !skills.value.includes(normalized)) skills.value.push(normalized)
  }
  function removeSkill(skill: string) { skills.value = skills.value.filter((item) => item !== skill) }
  async function loadApplications(allAccounts=false) { applications.value = (await careerLensApi.listApplications(undefined,undefined,allAccounts)).map(applicationView) }
  async function moveApplication(id: string, stage: ApplicationStage) {
    const updated = await careerLensApi.updateApplication(id, { stage, nextActionSet: false })
    const index = applications.value.findIndex((item) => item.id === id)
    if (index >= 0) applications.value[index] = applicationView(updated)
    addToast('投递状态已更新', `${updated.company} · ${updated.role}`)
  }
  async function addApplication(input: Pick<JobApplication, 'company' | 'role' | 'location'> & { stage?: ApplicationStage }) {
    const created = await careerLensApi.createApplication({
      ...input, stage: input.stage ?? 'wishlist', matchScore: 0, nextAction: '', logoText: input.company.slice(0, 1),
      logoTone: '#eef4f0', tags: [], actionType: 'MANUAL',
    })
    applications.value.unshift(applicationView(created))
    addToast('岗位已加入待投递', `${input.company} · ${input.role}`)
  }
  async function deleteApplication(id:string) {
    await careerLensApi.deleteApplication(id)
    applications.value = applications.value.filter(item => item.id !== id)
    addToast('投递记录已删除')
  }

  async function importResume(title: string, content: Record<string, unknown>) {
    const profile = record(content.profile)
    const created = await careerLensApi.createResume({
      title: title.trim() || '导入的简历',
      headline: text(profile.headline),
      content:normalizeResume(content),
      source: 'IMPORT',
    })
    resumes.value.unshift(created.resume)
    await loadResume(created.resume.id)
    addToast('简历导入完成', created.resume.title)
  }

  async function exportWorkspace() {
    const payload=await careerLensApi.backupWorkspace()
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `careerlens-workspace-${new Date().toISOString().slice(0, 10)}.json`
    anchor.click()
    URL.revokeObjectURL(url)
  }
  function reset() {
    initialized.value=false; originalContent={}; activeResumeId.value=''; activeResumeVersionId.value='';
    resumeVersion.value=0; resumes.value=[]; resumeVersions.value=[];
    applications.value=[]; skills.value=[];
    Object.assign(resume,emptyResume()); dynamicSections.value=[]
  }
  function toggleTheme() {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
    document.documentElement.dataset.theme = theme.value
    localStorage.setItem('careerlens-theme', theme.value)
  }
  function initializeTheme() {
    const saved = localStorage.getItem('careerlens-theme')
    if (saved === 'dark' || saved === 'light') theme.value = saved
    document.documentElement.dataset.theme = theme.value
  }

  return {
    importPending, sourceDocument, dynamicSections, resumeLoading, saveConflict, draftRevision, reset, theme, initialized, loading, error, resume, resumeVersion, activeResumeId, activeResumeVersionId,
    activeResumeSummary, completionRate, resumes, resumeVersions, skills, applications,
    toasts, initialize, loadResume,
    createResume, saveDraft, publishResume, reparseResume, duplicateResume, deleteResume, restoreResumeVersion,
    addSkill, removeSkill, loadApplications, moveApplication, addApplication, deleteApplication,
    exportWorkspace, importResume, addToast, removeToast, toggleTheme, initializeTheme, serializeResume,
  }
})
