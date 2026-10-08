<script setup lang="ts">
import { computed } from 'vue'
import { useWorkspaceStore } from '@/stores/workspace'
import type { ResumeEntry, ResumeSection } from '@/domain/resume'
withDefaults(defineProps<{activeSection?:string;compact?:boolean}>(),{activeSection:'',compact:false})
const w=useWorkspaceStore()
function entries(s:ResumeSection){return (s.items as ResumeEntry[]).filter(i=>!i.hidden&&(i.title||i.school||i.company||i.description||i.highlights.some(Boolean)))}
const visible=computed(()=>w.dynamicSections.filter(s=>!s.hidden&&(s.type==='SKILLS'?s.items.some(x=>typeof x==='string'&&x.trim()):entries(s).length)))
const contact=computed(()=>[w.resume.phone,w.resume.wechat?`微信 ${w.resume.wechat}`:'',w.resume.email,w.resume.location,w.resume.website].filter(Boolean).join(' · '))
</script>
<template><article class="resume-paper" :class="{'resume-paper--compact':compact}">
<header class="resume-paper__header" :class="{'resume-paper__header--photo':w.resume.photoDataUrl}"><img v-if="w.resume.photoDataUrl" class="resume-paper__photo" :src="w.resume.photoDataUrl" alt="简历照片"/><div><h1>{{w.resume.name}}</h1><p class="resume-paper__headline">{{w.resume.headline}}</p><p class="resume-paper__contact">{{contact}}</p></div></header>
<section v-if="w.resume.summary" class="resume-paper__section"><h2>个人简介</h2><p>{{w.resume.summary}}</p></section>
<section v-for="s in visible" :key="s.elementId" class="resume-paper__section"><h2>{{s.heading}}</h2><p v-if="s.type==='SKILLS'">{{(s.items as string[]).filter(x=>x.trim()).join(' · ')}}</p>
<template v-else><div v-for="item in entries(s)" :key="item.elementId" class="resume-paper__entry"><div class="resume-paper__entry-head"><strong>{{item.school||item.title||item.company}}</strong><span>{{item.period}}</span></div><p>{{[item.company,item.role,item.major,item.degree].filter(Boolean).join(' · ')}}</p><p v-if="item.description">{{item.description}}</p><ul v-if="item.highlights.filter(x=>x.trim()).length"><li v-for="(line,index) in item.highlights.filter(x=>x.trim())" :key="index">{{line}}</li></ul></div></template>
</section></article></template>
