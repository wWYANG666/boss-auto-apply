<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { RouterLink, onBeforeRouteLeave } from 'vue-router'
import { AlertCircle, Check, ChevronRight, Cloud, FileCheck2 } from '@lucide/vue'
import { newEntry, newSection, sectionLabels, type ResumeEntry, type ResumeSection } from '@/domain/resume'
import ResumePreview from '@/components/resume/ResumePreview.vue'
import ConfirmDialog from '@/components/feedback/ConfirmDialog.vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { careerLensApi } from '@/api/careerlens'
const w=useWorkspaceStore()
interface ImportField {path:string;value:string;quote:string;page:number|null;confidence:number}
const importedFields=computed<ImportField[]>(()=>{
 const analysis=w.sourceDocument.importAnalysis as {fields?:ImportField[]}|undefined
 return Array.isArray(analysis?.fields)?analysis.fields:[]
})
const canReparse=computed(()=>typeof w.sourceDocument.rawText==='string'&&w.sourceDocument.rawText.trim().length>0)
const lowConfidenceFields=computed(()=>importedFields.value.filter(field=>field.confidence<0.8))
const publishing=ref(false)
const readiness=computed(()=>[
 {label:'姓名',ready:Boolean(w.resume.name.trim())},
 {label:'求职方向',ready:Boolean(w.resume.headline.trim())},
 {label:'联系方式',ready:Boolean(w.resume.email.trim()||w.resume.phone.trim())},
 {label:'项目或经历',ready:w.dynamicSections.some(section=>!section.hidden&&section.type!=='SKILLS'&&section.items.some(item=>typeof item!=='string'&&!item.hidden&&Boolean(item.title.trim()||item.school.trim()||item.description.trim()||item.highlights.some(value=>value.trim()))))},
])
const readyCount=computed(()=>readiness.value.filter(item=>item.ready).length)
const fieldNames:Record<string,string>={name:'姓名',headline:'求职方向',email:'邮箱',phone:'电话',wechat:'微信',location:'城市',website:'主页',
 school:'学校',major:'专业',degree:'学历',period:'时间',company:'公司',role:'岗位 / 角色',title:'名称',summary:'个人简介'}
function fieldName(path:string){return fieldNames[path.split('/').at(-1)??'']??path}
function entryCount(type:string){return w.dynamicSections.filter(s=>s.type===type).reduce((sum,s)=>sum+s.items.length,0)}

const savingState=ref<'saved'|'saving'|'error'>('saved'), failure=ref('')
const reparsing=ref(false)
const mobilePane=ref<'edit'|'preview'>('edit'), sectionType=ref('PROJECT')
let timer:number|undefined
let generation=0
watch(()=>JSON.stringify(w.serializeResume()),()=>{
 if(w.loading||w.resumeLoading)return
 generation++
 savingState.value='saving'
 clearTimeout(timer)
 timer=window.setTimeout(save,650)
})
async function save(){
 clearTimeout(timer);const started=generation
 try{await w.saveDraft(); if(started===generation)savingState.value='saved';failure.value=''}
 catch(error){savingState.value='error';failure.value=error instanceof Error?error.message:String(error)}
}
async function rename(){if(w.activeResumeSummary)await careerLensApi.renameResume(w.activeResumeId,w.activeResumeSummary.title)}
async function publish(){
 if(w.importPending||publishing.value)return
 publishing.value=true
 try{await save();if(savingState.value==='error')return;await w.publishResume()}
 finally{publishing.value=false}
}
const confirmAction=ref<'reload'|'reparse'|null>(null)
function reload(){confirmAction.value='reload'}
async function reparse(){
 confirmAction.value='reparse'
}
async function runConfirmedAction(){
 if(confirmAction.value==='reload'){await w.loadResume(w.activeResumeId);confirmAction.value=null;return}
 await save();if(savingState.value==='error')return
 reparsing.value=true
 try{await w.reparseResume();savingState.value='saved';failure.value=''}
 catch(error){savingState.value='error';failure.value=error instanceof Error?error.message:String(error)}
 finally{reparsing.value=false;confirmAction.value=null}
}
function downloadDraft(){
 const link=document.createElement('a'),url=URL.createObjectURL(new Blob([JSON.stringify(w.serializeResume(),null,2)],{type:'application/json'}))
 link.href=url;link.download='unsaved-resume.json';link.click();URL.revokeObjectURL(url)
}
function add(){w.dynamicSections.push(newSection(sectionType.value))}
function scrollToSection(id:string){document.getElementById(id)?.scrollIntoView({behavior:'smooth',block:'start'})}
function entries(section:ResumeSection){return section.items as ResumeEntry[]}
function move(index:number,direction:number){
 const target=index+direction;if(target<0||target>=w.dynamicSections.length)return
 const item=w.dynamicSections.splice(index,1)[0]!;w.dynamicSections.splice(target,0,item)
}
function editSkills(section:ResumeSection,event:Event){section.items=(event.target as HTMLTextAreaElement).value.split(/[,，\n]/)}
function highlights(item:ResumeEntry,event:Event){item.highlights=(event.target as HTMLTextAreaElement).value.split('\n')}
onBeforeRouteLeave(async()=>{if(savingState.value==='saving')await save();if(savingState.value==='error')return false})
onBeforeUnmount(()=>clearTimeout(timer))
</script>
<template><div class="editor-page">
<header class="editor-toolbar">
<div class="editor-toolbar__left"><RouterLink to="/resumes" class="button button--quiet">返回</RouterLink><div class="editor-title"><input v-if="w.activeResumeSummary" aria-label="资料名称" v-model="w.activeResumeSummary.title" @blur="rename"/><strong v-else>求职资料</strong><span class="autosave-status" :class="`autosave-status--${savingState}`"><Cloud :size="12"/> {{savingState==='saved'?`草稿已保存 · 修订 ${w.draftRevision}`:savingState==='saving'?'正在自动保存草稿':'草稿保存失败'}}</span></div></div>
<div class="editor-toolbar__right"><span class="published-version-status"><FileCheck2 :size="14"/> {{w.resumeVersion?`已发布 v${w.resumeVersion}`:'尚未发布'}}</span><button class="button button--primary" :disabled="publishing||w.importPending||savingState==='error'" @click="publish">{{publishing?'正在发布…':w.resumeVersion?'发布新版本':'发布投递版本'}}</button></div>
</header>
<section class="editor-readiness panel"><div><span class="eyebrow">发布准备</span><h2>{{readyCount}} / {{readiness.length}} 项基础资料已完成</h2><p>草稿会自动保存；点击发布后生成新的不可变投递版本。</p></div><div><span v-for="item in readiness" :key="item.label" :class="{'ready':item.ready}"><i><Check v-if="item.ready" :size="11"/></i>{{item.label}}</span></div></section>
<div v-if="w.importPending" class="panel import-review-banner"><header><span><AlertCircle :size="20"/></span><div><small>发布前必须完成</small><h2>导入内容待确认</h2><p>已回填 {{importedFields.length}} 项字段，其中 {{lowConfidenceFields.length}} 项需要重点核对。教育 {{entryCount('EDUCATION')}} 条、项目 {{entryCount('PROJECT')}} 条、工作/实习 {{entryCount('EXPERIENCE')}} 条。</p></div></header>
<details v-if="importedFields.length"><summary>查看字段识别依据</summary><p>以下是导入时的识别结果；后续编辑以当前表单为准。标注“待重点核对”的字段来自推断，不代表已确认。</p>
<div class="import-evidence-list"><article v-for="(field,index) in importedFields" :key="index">
<strong>{{fieldName(field.path)}}：{{field.value}}</strong><small>{{field.confidence>=0.8?'规则识别':'待重点核对'}} · {{field.page?'第 '+field.page+' 页':'原文'}} </small>
<p>{{field.quote}}</p></article></div></details><details><summary>查看原始文本与定位数据</summary><pre class="import-source-text">{{w.sourceDocument.rawText}}</pre><pre class="import-source-text">{{w.sourceDocument.blocks}}</pre></details><footer><button v-if="canReparse" class="button button--secondary" :disabled="reparsing" @click="reparse">{{reparsing?'正在重新识别…':'重新识别导入内容'}}</button> <button class="button button--primary" @click="w.importPending=false">我已逐项核对</button></footer></div>
<div v-else-if="canReparse" class="panel import-reparse-note"><div><strong>导入识别不完整？</strong><p>可以使用已保存的原文重新识别项目、教育和工作经历。</p></div><button class="button button--secondary" :disabled="reparsing" @click="reparse">{{reparsing?'正在重新识别…':'重新识别导入内容'}}</button></div>
<div v-if="failure" role="alert" class="panel editor-save-error"><AlertCircle :size="20"/><div><strong>草稿保存失败</strong><p>{{failure}}</p></div><button class="button" @click="save">重试保存</button><button class="button" @click="downloadDraft">保留本地 JSON</button><button class="button" @click="reload">加载服务端版本</button></div>
<div class="editor-mobile-switch"><button :class="{active:mobilePane==='edit'}" @click="mobilePane='edit'">编辑内容</button><button :class="{active:mobilePane==='preview'}" @click="mobilePane='preview'">实时预览</button></div>
<div class="resume-editor-layout" :class="'resume-editor-layout--'+mobilePane">
<aside class="editor-section-nav"><h2>投递资料</h2><p>建议只保留技能、项目和必要经历，供筛选和招呼语使用。</p><nav aria-label="资料章节"><button type="button" @click="scrollToSection('editor-profile')"><span>基本信息</span><ChevronRight :size="13"/></button><button v-for="section in w.dynamicSections" :key="section.elementId" type="button" @click="scrollToSection(`section-${section.elementId}`)"><span>{{section.heading}}</span><ChevronRight :size="13"/></button></nav><select aria-label="新增资料类型" v-model="sectionType"><option v-for="(label,type) in sectionLabels" :key="type" :value="type">{{label}}</option></select><button class="button button--primary" @click="add">添加资料</button></aside>
<section class="editor-form-pane"><form class="resume-form" @submit.prevent>
<section id="editor-profile" class="form-section"><h2>基本信息</h2><div class="form-grid form-grid--2">
<label class="field"><span>姓名</span><input v-model="w.resume.name"/></label><label class="field"><span>求职方向</span><input v-model="w.resume.headline"/></label>
<label class="field"><span>邮箱</span><input v-model="w.resume.email" type="email"/></label><label class="field"><span>手机号</span><input v-model="w.resume.phone"/></label>
<label class="field"><span>微信</span><input v-model="w.resume.wechat"/></label>
<label class="field"><span>所在城市</span><input v-model="w.resume.location"/></label><label class="field"><span>个人主页</span><input v-model="w.resume.website"/></label>
</div><div v-if="w.resume.photoDataUrl" class="imported-photo"><img :src="w.resume.photoDataUrl" alt="已导入的简历照片"/><span>已从 PDF 导入照片</span></div><label class="field"><span>三句话概括你的方向、能力和特点</span><textarea v-model="w.resume.summary" rows="4"></textarea></label></section>
<section v-for="(section,index) in w.dynamicSections" :id="`section-${section.elementId}`" :key="section.elementId" class="form-section dynamic-section">
<header class="form-section__heading"><input aria-label="章节名称" v-model="section.heading"/><div><button class="button button--quiet" :disabled="index===0" @click="move(index,-1)">上移</button><button class="button button--quiet" :disabled="index===w.dynamicSections.length-1" @click="move(index,1)">下移</button><button class="button button--quiet" @click="w.dynamicSections.splice(index,1)">删除章节</button></div></header>
<label><input type="checkbox" v-model="section.hidden"/>隐藏此章节（不导出、不评分）</label>
<label v-if="section.type==='SKILLS'" class="field"><span>技能（逗号或换行分隔）</span><textarea :value="section.items.join('\n')" @input="editSkills(section,$event)"></textarea></label>
<template v-else>
<article v-for="(item,itemIndex) in entries(section)" :key="item.elementId" class="panel editor-entry-card">
<div class="form-grid form-grid--2">
<template v-if="section.type==='EDUCATION'"><label class="field"><span>学校</span><input v-model="item.school"/></label><label class="field"><span>专业</span><input v-model="item.major"/></label><label class="field"><span>学历</span><input v-model="item.degree"/></label></template>
<template v-else><label class="field"><span>{{section.type==='PROJECT'?'项目名称':'名称'}}</span><input v-model="item.title"/></label><label class="field"><span>公司 / 组织</span><input v-model="item.company"/></label><label class="field"><span>你的角色</span><input v-model="item.role"/></label></template>
<label class="field"><span>起止时间</span><input v-model="item.period"/></label></div>
<label class="field"><span>说明</span><textarea v-model="item.description" rows="3"></textarea></label>
<label class="field"><span>成果与亮点（每行一条）</span><textarea :value="item.highlights.join('\n')" @input="highlights(item,$event)" rows="3"></textarea></label>
<label><input type="checkbox" v-model="item.hidden"/>隐藏条目</label><button class="button button--quiet" @click="section.items.splice(itemIndex,1)">删除条目</button>
</article><button class="button button--secondary" @click="entries(section).push(newEntry())">添加条目</button>
</template>
</section></form></section>
<aside class="editor-preview-pane"><div class="preview-toolbar"><strong>当前可见内容预览</strong></div><div class="preview-canvas"><ResumePreview/></div></aside>
</div><ConfirmDialog :open="confirmAction!==null" :busy="reparsing" danger :title="confirmAction==='reload'?'加载服务端版本？':'重新识别导入内容？'" :description="confirmAction==='reload'?'本页尚未保存的修改将被服务端版本替换。':'导入原文会重新生成当前草稿字段。建议先下载本地 JSON 备份。'" :confirm-label="confirmAction==='reload'?'确认加载':'确认重新识别'" @close="confirmAction=null" @confirm="runConfirmedAction" /></div></template>
