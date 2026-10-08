<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, CheckCircle2, FileText, Upload, AlertCircle } from '@lucide/vue'
import { useRouter } from 'vue-router'
import { useWorkspaceStore } from '@/stores/workspace'
import { careerLensApi } from '@/api/careerlens'
const workspace = useWorkspaceStore()
const router = useRouter()
const busy = ref(false)
const importError=ref('')
const resumeStatus=computed(()=>{
 const resume=workspace.activeResumeSummary
 if(!resume)return null
 if(!resume.currentVersion)return{label:'尚未发布',hint:'编辑并发布后才能用于岗位发现',tone:'warning'}
 if(resume.completionRate<60)return{label:`已发布 v${resume.currentVersion}`,hint:'资料内容较少，建议继续完善',tone:'warning'}
 return{label:`已发布 v${resume.currentVersion}`,hint:'可用于岗位匹配与招呼语',tone:'success'}
})
async function create() { busy.value=true; try { await workspace.createResume(); await router.push('/resumes/editor') } finally {busy.value=false} }
async function open(id:string){await workspace.loadResume(id); await router.push('/resumes/editor')}
async function importJson(event:Event){
 const input=event.target as HTMLInputElement
 const file=input.files?.[0]; if(!file||busy.value)return
 busy.value=true
 importError.value=''
 try {
 if(file.size>8*1024*1024)throw new Error('请选择不超过8MB的简历文件')
 if(!file.name.toLowerCase().endsWith('.json')){
   const result=await careerLensApi.importResumeFile(file)
   await workspace.initialize(true); await workspace.loadResume(result.resume.id)
   workspace.addToast('文档字段候选已导入','发布前需要确认。')
   await router.push('/resumes/editor')
   return
 }
 const content=JSON.parse(await file.text())
 if(!content.basics && (!content.profile || !Array.isArray(content.sections))) throw new Error('请使用包含 profile 和 sections 的 CareerLens JSON 简历')
 await workspace.importResume(file.name.replace(/\.json$/i,''),content)
 await router.push('/resumes/editor')
 } catch(cause){importError.value=cause instanceof Error?cause.message:'导入失败，请检查文件后重试'}
 finally {busy.value=false;input.value=''}
}
</script>
<template>
<div class="page resumes-page">
<header class="page-header"><div><p class="eyebrow">自动投递所需资料</p><h1>求职资料</h1><p class="page-subtitle">维护用于岗位匹配和招呼语的主资料；草稿只有发布后才会进入正式流程。</p></div><div class="page-header__actions"><label class="button button--secondary"><Upload :size="16"/> {{busy?'正在识别资料…':'导入求职资料'}}<input :disabled="busy" type="file" accept=".json,.txt,.pdf,.docx" hidden @change="importJson"/></label><button v-if="!workspace.resumes.length" class="button button--primary" :disabled="busy" @click="create">创建求职资料</button></div></header>
<div v-if="importError" class="resume-import-error" role="alert"><AlertCircle :size="18"/><span><strong>资料导入失败</strong>{{importError}}</span></div>
<div v-if="!workspace.resumes.length" class="empty-state panel"><h2>还没有求职资料</h2><p>填写姓名、求职方向、技能和项目亮点后即可开始筛选岗位。</p><button class="button button--primary" :disabled="busy" @click="create">创建求职资料</button></div>
<section class="resume-card-grid resume-card-grid--primary">
<article v-if="workspace.activeResumeSummary" class="resume-library-card resume-primary-card">
 <div class="resume-primary-card__icon"><FileText :size="24"/></div>
 <div class="resume-library-card__body"><div class="resume-primary-card__heading"><div><span class="eyebrow">当前主资料</span><h2>{{ workspace.activeResumeSummary.title }}</h2><p>{{ workspace.activeResumeSummary.headline || '尚未填写求职方向' }}</p></div><span v-if="resumeStatus" class="resume-status-pill" :class="`resume-status-pill--${resumeStatus.tone}`"><CheckCircle2 v-if="resumeStatus.tone==='success'" :size="13"/><AlertCircle v-else :size="13"/>{{resumeStatus.label}}</span></div>
 <div class="resume-completion"><span><strong>资料完成度</strong><b>{{workspace.activeResumeSummary.completionRate}}%</b></span><div><i :style="{width:`${workspace.activeResumeSummary.completionRate}%`}"></i></div><small>{{resumeStatus?.hint}}</small></div>
 <div class="resume-library-card__meta"><span>草稿自动保存</span><span>{{ workspace.activeResumeSummary.currentVersion ? `正式版本 v${workspace.activeResumeSummary.currentVersion}` : '没有正式版本' }}</span><span>更新于 {{ new Date(workspace.activeResumeSummary.updatedAt).toLocaleDateString() }}</span></div>
 <div class="resume-primary-card__actions"><button class="button button--primary" @click="open(workspace.activeResumeSummary.id)">{{workspace.activeResumeSummary.currentVersion?'继续编辑':'完善并发布'}} <ArrowRight :size="15"/></button></div></div>
</article>
</section>
</div>
</template>
