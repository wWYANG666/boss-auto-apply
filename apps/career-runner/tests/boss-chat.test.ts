import assert from 'node:assert/strict'
import { test } from 'node:test'
import protobuf from 'protobufjs'
import { buildBossTextPayload, createBossConversationWithGreeting } from '../src/adapters/boss-chat.js'
import type { BrowserContext } from 'playwright-core'

test('BOSS chat payload encodes exact reviewed text and participant ids',()=>{
  const schema='message U{required int64 uid=1;optional string name=2;}message B{required int32 type=1;required int32 templateId=2;optional string text=3;}message M{required U from=1;required U to=2;required int32 type=3;optional int64 mid=4;optional int64 time=5;required B body=6;optional int64 cmid=11;}message P{required int32 type=1;repeated M messages=3;}'
  const root=protobuf.parse(schema).root,type=root.lookupType('P')
  const decoded=type.toObject(type.decode(buildBossTextPayload(101,202,'enc-boss','审核后的招呼语',12345)),{longs:Number}) as any
  assert.equal(decoded.messages[0].from.uid,101)
  assert.equal(decoded.messages[0].to.uid,202)
  assert.equal(decoded.messages[0].to.name,'enc-boss')
  assert.equal(decoded.messages[0].body.text,'审核后的招呼语')
})

test('new BOSS conversation saves reviewed greeting before one contact request',async()=>{
  const calls:Array<{url:string;options:unknown}>=[]
  const responses=[{status:()=>200,json:async()=>({code:0})},{status:()=>200,json:async()=>({code:0})}]
  const context={request:{post:async(url:string,options:unknown)=>{calls.push({url,options});return responses[calls.length-1]}}} as unknown as BrowserContext
  const result=await createBossConversationWithGreeting(context,'token','job-1','审核后的招呼语','/wapi/zpgeek/friend/add.json?jobId=job-1')
  assert.equal(result.code,0)
  assert.deepEqual(calls.map(call=>call.url),[
    'https://www.zhipin.com/wapi/zpchat/greeting/custom/saveV2',
    'https://www.zhipin.com/wapi/zpgeek/friend/add.json?jobId=job-1',
  ])
  assert.deepEqual((calls[0]!.options as {form:unknown}).form,{templateId:'',content:'审核后的招呼语',customType:'2'})
})
