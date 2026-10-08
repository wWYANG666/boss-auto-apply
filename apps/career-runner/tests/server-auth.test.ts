import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createHmac, createHash, randomUUID } from 'node:crypto'
import { DeviceAuth } from '../src/core/device-auth.js'
import { createRunnerApp } from '../src/server.js'
import { TaskEngine } from '../src/core/task-engine.js'
import { JsonlJournal } from '../src/core/journal.js'
import { EventHub } from '../src/core/events.js'
import { ApprovalService } from '../src/core/approval.js'
import { AdapterRegistry } from '../src/adapters/registry.js'

test('all business routes require a paired owner; token is not returned in status',async()=>{
 process.env.RUNNER_MODE='fake'
 const dir=await mkdtemp(join(tmpdir(),'runner-http-')), owner='00000000-0000-4000-8000-000000000001'
 const auth=new DeviceAuth(dir);await auth.initialize()
 const events=new EventHub(), registry=new AdapterRegistry()
 const engine=new TaskEngine(new JsonlJournal(dir),events,registry,new ApprovalService('a'.repeat(32),join(dir,'consumed')))
 await engine.initialize()
 const server=createRunnerApp(engine,events,registry,auth).listen(0,'127.0.0.1')
 await new Promise<void>(resolve=>server.once('listening',resolve))
 const address=server.address()
 assert.ok(address && typeof address!=='string')
 const base='http://127.0.0.1:'+address.port
 try{
  for(const path of ['/v1/tasks','/v1/platforms','/v1/events'])assert.equal((await fetch(base+path)).status,401)
  assert.equal((await fetch(base+'/v1/approvals',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).status,401)
  const {code}=JSON.parse(await readFile(join(dir,'pairing.json'),'utf8'))
  const paired=await fetch(base+'/v1/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ownerId:owner,code})})
  assert.equal(paired.status,200)
  const token=(await paired.json()).data.token
  const timestamp=String(Date.now()),nonce=randomUUID()
  const input=['GET','/v1/tasks',timestamp,nonce,createHash('sha256').update('').digest('hex')].join('\n')
  const signature=createHmac('sha256',token).update(input).digest('hex')
  const headers={Authorization:'Bearer '+token,'X-Runner-Owner-Id':owner,
    'X-Runner-Timestamp':timestamp,'X-Runner-Nonce':nonce,'X-Runner-Signature':signature}
  assert.equal((await fetch(base+'/v1/tasks',{headers})).status,200)
  assert.equal((await fetch(base+'/v1/tasks',{headers})).status,401)
  assert.equal((await fetch(base+'/v1/tasks',{headers:{...headers,'X-Runner-Owner-Id':'other'}})).status,401)
  assert.ok(!(await fetch(base+'/health').then(r=>r.text())).includes(token))
 }finally{server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()))}
})
