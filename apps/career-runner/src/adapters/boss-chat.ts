import mqtt from 'mqtt'
import type { BrowserContext, Page } from 'playwright-core'

type FriendRecord={uid:number;encryptUid:string;encryptBossId:string;securityId:string;encryptJobId:string;lastMsg?:string}
type ChatEvidence={friend:FriendRecord;messageId?:string;messageStatus?:number;resumeEvidence:boolean}
export type BossContactResult={stage:'greeting'|'contact';httpStatus:number;code:number;message:string}

export async function createBossConversationWithGreeting(context:BrowserContext,token:string,externalJobId:string,
  reviewedText:string,contactUrl:string):Promise<BossContactResult>{
  const greeting=await context.request.post('https://www.zhipin.com/wapi/zpchat/greeting/custom/saveV2',{
    form:{templateId:'',content:reviewedText,customType:'2'},headers:{Zp_token:token},
  })
  const greetingPayload=await greeting.json().catch(()=>null)
  if(greeting.status()!==200||Number(greetingPayload?.code??-1)!==0)
    return{stage:'greeting',httpStatus:greeting.status(),code:Number(greetingPayload?.code??-1),message:String(greetingPayload?.message??'自定义招呼语保存失败')}
  const response=await context.request.post(`https://www.zhipin.com${contactUrl}`,{headers:{Zp_token:token}})
  const payload=await response.json().catch(()=>null)
  return{stage:'contact',httpStatus:response.status(),code:Number(payload?.code??-1),
    message:String(payload?.zpData?.bizData?.chatRemindDialog?.content??payload?.message??'')}
}

function varint(value:number):number[]{
  const bytes:number[]=[];let remaining=BigInt(Math.trunc(value))
  do{let byte=Number(remaining&0x7fn);remaining>>=7n;if(remaining)byte|=0x80;bytes.push(byte)}while(remaining)
  return bytes
}
function field(number:number,wire:number,data:number[]):number[]{return[...varint((number<<3)|wire),...data]}
function variable(number:number,value:number):number[]{return field(number,0,varint(value))}
function bytes(number:number,value:number[]):number[]{return field(number,2,[...varint(value.length),...value])}
function text(number:number,value:string):number[]{return bytes(number,[...new TextEncoder().encode(value)])}
function user(uid:number,encryptUid=''):number[]{return[...variable(1,uid),...(encryptUid?text(2,encryptUid):[])]}

export function buildBossTextPayload(fromUid:number,toUid:number,toEncryptUid:string,message:string,clientMid=Date.now()):Uint8Array{
  const body=[...variable(1,1),...variable(2,1),...text(3,message)]
  const chat=[...bytes(1,user(fromUid)),...bytes(2,user(toUid,toEncryptUid)),...variable(3,1),
    ...variable(4,clientMid),...variable(5,Date.now()),...bytes(6,body),...variable(11,clientMid)]
  return Uint8Array.from([...variable(1,1),...bytes(3,chat)])
}

async function friendForJob(context:BrowserContext,externalJobId:string):Promise<FriendRecord|null>{
  for(let pageNumber=1;pageNumber<=5;pageNumber++){
    const response=await context.request.get(`https://www.zhipin.com/wapi/zprelation/friend/getGeekFriendList.json?page=${pageNumber}`)
    const payload=await response.json().catch(()=>null)
    const rows=Array.isArray(payload?.zpData?.result)?payload.zpData.result:[]
    const item=rows.find((value:Record<string,unknown>)=>String(value.encryptJobId??'')===externalJobId)
    if(item)return{uid:Number(item.uid),encryptUid:String(item.encryptUid??item.encryptBossId??''),
      encryptBossId:String(item.encryptBossId??item.encryptUid??''),securityId:String(item.securityId??''),
      encryptJobId:String(item.encryptJobId??''),lastMsg:String(item.lastMsg??'')}
    if(!rows.length)break
  }
  return null
}

async function session(context:BrowserContext):Promise<{uid:number;token:string;wt:string}>{
  const user=await (await context.request.get('https://www.zhipin.com/wapi/zpuser/wap/getUserInfo.json')).json().catch(()=>null)
  const wt=await (await context.request.get('https://www.zhipin.com/wapi/zppassport/get/wt')).json().catch(()=>null)
  return{uid:Number(user?.zpData?.userId??0),token:String(user?.zpData?.token??''),wt:String(wt?.zpData?.wt2??'')}
}

async function history(context:BrowserContext,friend:FriendRecord,reviewedText:string):Promise<ChatEvidence>{
  const auth=await session(context)
  const headers=auth.token?{Zp_token:auth.token}:undefined
  const bossData=await (await context.request.get(`https://www.zhipin.com/wapi/zpchat/geek/getBossData?bossId=${friend.uid}`,{headers})).json().catch(()=>null)
  const securityId=String(bossData?.zpData?.data?.securityId??bossData?.zpData?.securityId??friend.securityId)
  const query=new URLSearchParams({gid:String(friend.uid),c:'20',src:'0',securityId})
  const payload=await (await context.request.get(`https://www.zhipin.com/wapi/zpchat/geek/historyMsg?${query}`,{headers})).json().catch(()=>null)
  const messages=Array.isArray(payload?.zpData?.messages)?payload.zpData.messages:Array.isArray(payload?.zpData?.msgList)?payload.zpData.msgList:[]
  const exact=messages.find((item:Record<string,unknown>)=>String((item.body as Record<string,unknown>|undefined)?.text??'')===reviewedText)
  const resume=messages.some((item:Record<string,unknown>)=>{const body=item.body as Record<string,unknown>|undefined;return Boolean(body?.resume)||Number(body?.type)===12||/简历/.test(String(body?.text??''))})
  friend.securityId=securityId||friend.securityId
  return{friend,messageId:exact?String(exact.mid??exact.msgId??''):undefined,messageStatus:exact?Number(exact.status??1):undefined,resumeEvidence:resume}
}

export async function inspectBossConversation(page:Page,externalJobId:string,reviewedText:string,context?:BrowserContext):Promise<ChatEvidence|null>{
  const browserContext=context??page.context()
  const friend=await friendForJob(browserContext,externalJobId)
  return friend?history(browserContext,friend,reviewedText):null
}

export async function sendBossReviewedText(page:Page,context:BrowserContext,externalJobId:string,reviewedText:string):Promise<ChatEvidence>{
  const existing=await inspectBossConversation(page,externalJobId,reviewedText,context)
  if(!existing)throw new Error('BOSS_FRIEND_NOT_FOUND: 未找到该岗位的沟通会话。')
  if(existing.messageId)return existing
  const auth=await session(context)
  if(!auth.uid||!auth.token||!auth.wt)throw new Error('BOSS_CHAT_AUTH_MISSING: 缺少聊天认证信息。')
  const cookieHeader=(await context.cookies('https://www.zhipin.com')).map(cookie=>`${cookie.name}=${cookie.value}`).join('; ')
  const client=mqtt.connect('wss://ws6.zhipin.com:443/chatws',{clientId:`ws-${crypto.randomUUID().replaceAll('-','').slice(0,16)}`,
    username:`${auth.token}|0`,password:auth.wt,keepalive:25,clean:true,reconnectPeriod:0,connectTimeout:12_000,protocolVersion:4,
    wsOptions:{headers:{Origin:'https://www.zhipin.com',Cookie:cookieHeader}}})
  try{
    await new Promise<void>((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('BOSS_CHAT_CONNECT_TIMEOUT')),12_000)
      client.once('connect',()=>{clearTimeout(timer);resolve()});client.once('error',error=>{clearTimeout(timer);reject(error)})})
    const payload=buildBossTextPayload(auth.uid,existing.friend.uid,existing.friend.encryptUid,reviewedText)
    await client.publishAsync('chat',Buffer.from(payload),{qos:1,retain:false})
  }finally{await client.endAsync().catch(()=>undefined)}
  for(let attempt=0;attempt<10;attempt++){
    await new Promise(resolve=>setTimeout(resolve,700))
    const observed=await history(context,existing.friend,reviewedText)
    if(observed.messageId)return observed
  }
  throw new Error('BOSS_CHAT_DELIVERY_NOT_OBSERVED: MQTT已发布，但聊天记录中没有审核文本。')
}
