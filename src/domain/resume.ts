export interface ResumeEntry extends Record<string, unknown> {
  elementId: string
  title: string
  company: string
  role: string
  school: string
  major: string
  degree: string
  period: string
  description: string
  highlights: string[]
  hidden: boolean
}
export interface ResumeSection extends Record<string, unknown> {
  elementId: string
  type: string
  heading: string
  hidden: boolean
  items: ResumeEntry[] | string[]
}
export interface ResumeDocument extends Record<string,unknown>{
  schemaVersion: string
  profile: Record<string, unknown>
  sections: ResumeSection[]
}
const object=(v:unknown):Record<string,unknown>=>v&&typeof v==='object'&&!Array.isArray(v)?v as Record<string,unknown>:{}
const text=(v:unknown)=>typeof v==='string'?v:''
export const sectionLabels:Record<string,string>={PROJECT:'项目经历',EDUCATION:'教育经历',EXPERIENCE:'实习 / 工作经历',SKILLS:'专业技能',AWARDS:'奖项 / 证书',OTHER:'自定义章节'}
export function newEntry(value:Record<string,unknown>={},id:string=crypto.randomUUID()):ResumeEntry {
  return {...value,elementId:text(value.elementId)||id,title:text(value.title),company:text(value.company),
    role:text(value.role),school:text(value.school),major:text(value.major),degree:text(value.degree),
    period:text(value.period),description:text(value.description)||text(value.text),
    highlights:Array.isArray(value.highlights)?value.highlights.filter((v):v is string=>typeof v==='string'):[],
    hidden:value.hidden===true||value.visible===false}
}
export function newSection(type:string,id=crypto.randomUUID()):ResumeSection {
  return {elementId:id,type,heading:sectionLabels[type]||type,hidden:false,items:type==='SKILLS'?[]:[newEntry()]}
}
export function normalizeResume(input:unknown):ResumeDocument {
  const source=object(input)
  const sections=(Array.isArray(source.sections)?source.sections:[]).map((raw,index)=>{
    const s=object(raw),type=text(s.type).toUpperCase()||'OTHER'
    const id=text(s.elementId)||'section-'+index
    const items=type==='SKILLS'
      ? (Array.isArray(s.items)?s.items.filter((x):x is string=>typeof x==='string'):[])
      : Array.isArray(s.items)&&s.items.every(x=>typeof x==='object'&&x!==null)
        ? s.items.map((x,i)=>newEntry(object(x),id+'-item-'+i))
        : [newEntry({...s,elementId:id+'-item-0'},id+'-item-0')]
    const section={...s,elementId:id,type,heading:text(s.heading)||sectionLabels[type]||type,
      hidden:s.hidden===true||s.visible===false,items} as ResumeSection
    for(const field of ['title','role','company','school','major','degree','period','description','text','highlights','visible'])delete section[field]
    return section
  })
  // JSON Resume is an exchange format; convert without discarding its original fields.
  if(!sections.length && source.basics){
    for(const [key,type] of [['work','EXPERIENCE'],['projects','PROJECT'],['education','EDUCATION'],['awards','AWARDS']] as const){
      if(Array.isArray(source[key])) sections.push({elementId:key,type,heading:sectionLabels[type]!,hidden:false,
        items:source[key].map((x,i)=>{const v=object(x);return newEntry({...v,title:v.name||v.title,company:v.name,
          role:v.position,school:v.institution,major:v.area,degree:v.studyType,description:v.summary,
          period:[v.startDate,v.endDate].filter(Boolean).join(' — ')},key+'-'+i)})})
    }
    if(Array.isArray(source.skills))sections.push({elementId:'skills',type:'SKILLS',heading:'专业技能',hidden:false,
      items:source.skills.flatMap(x=>{const v=object(x);return [text(v.name),...(Array.isArray(v.keywords)?v.keywords.map(text):[])].filter(Boolean)})})
  }
  const basics=object(source.basics), location=object(basics.location)
  const profile=source.profile?object(source.profile):{...basics,headline:text(basics.label),website:text(basics.url),location:text(location.city)}
  return {...source,schemaVersion:'2.0',profile,sections}
}
