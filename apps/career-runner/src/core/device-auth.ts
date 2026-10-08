import { randomBytes, createHash, createHmac, timingSafeEqual } from 'node:crypto'
import { mkdir, readFile, writeFile, rename, readdir, unlink } from 'node:fs/promises'
import { resolve } from 'node:path'

const digest = (value: string) => createHash('sha256').update(value).digest('hex')
type Binding = { ownerId: string; tokenHash: string; pendingHash?: string; revoked?: boolean }

/** One local Runner is paired to one Core user; secrets are never sent in status/events. */
export class DeviceAuth {
  private binding?: Binding
  private code = ''
  private expiresAt = 0
  private attempts = 0
  private pairing = false
  constructor(private directory: string) {}

  async initialize(): Promise<void> {
    await mkdir(this.directory, {recursive:true,mode:0o700})
    try {
      const value = JSON.parse(await readFile(resolve(this.directory,'device-binding.json'),'utf8')) as Binding
      if (!value.ownerId || !/^[a-f0-9]{64}$/.test(value.tokenHash)) throw new Error('Invalid device binding')
      this.binding=value.revoked ? undefined : value
    } catch(error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
    }
    if (!this.binding) {
      this.code=randomBytes(24).toString('base64url')
      this.expiresAt=Date.now()+120_000
      await writeFile(resolve(this.directory,'pairing.json'),
        JSON.stringify({code:this.code,expiresAt:new Date(this.expiresAt).toISOString()}),{mode:0o600})
    }
  }

  async pair(ownerId: string, code: string): Promise<string> {
    if (this.binding || this.pairing || Date.now()>=this.expiresAt || ++this.attempts>5
        || !code || digest(code)!==digest(this.code)) throw new Error('PAIRING_DENIED')
    this.pairing=true
    try {
      const token=randomBytes(32).toString('base64url')
      const binding={ownerId,tokenHash:digest(token)}
      const destination=resolve(this.directory,'device-binding.json')
      await writeFile(destination+'.tmp',JSON.stringify(binding),{mode:0o600})
      await rename(destination+'.tmp',destination)
      this.binding=binding
      this.code=''
      await writeFile(resolve(this.directory,'pairing.json'),JSON.stringify({paired:true}),{mode:0o600})
      return token
    } finally {this.pairing=false}
  }

  authorize(ownerId: string, token: string): boolean {
    if (!this.binding || ownerId!==this.binding.ownerId || !token) return false
    return [this.binding.tokenHash,this.binding.pendingHash].some(hash=>hash && timingSafeEqual(Buffer.from(hash,'hex'),Buffer.from(digest(token),'hex'))) 
  }

  private async persist(binding:Binding):Promise<void> {
    const path=resolve(this.directory,'device-binding.json')
    await writeFile(path+'.tmp',JSON.stringify(binding),{mode:0o600,flush:true})
    await rename(path+'.tmp',path)
    this.binding=binding.revoked ? undefined : binding
  }
  async beginRotation(token:string):Promise<void>{
    if(!this.binding || this.pairing)throw new Error('DEVICE_BUSY')
    if(this.binding.tokenHash===digest(token))return
    if(this.binding.pendingHash && this.binding.pendingHash!==digest(token))throw new Error('ROTATION_PENDING')
    this.pairing=true
    try{await this.persist({...this.binding,pendingHash:digest(token)})}finally{this.pairing=false}
  }
  async commitRotation(token:string):Promise<void>{
    if(!this.binding || this.pairing)throw new Error('DEVICE_BUSY')
    if(this.binding.tokenHash===digest(token))return
    if(this.binding.pendingHash!==digest(token))throw new Error('ROTATION_TOKEN_INVALID')
    this.pairing=true
    try{await this.persist({ownerId:this.binding.ownerId,tokenHash:digest(token)})}finally{this.pairing=false}
  }
  async revoke():Promise<void>{
    if(!this.binding || this.pairing)throw new Error('DEVICE_BUSY')
    this.pairing=true
    try {
      await this.persist({...this.binding,revoked:true})
      this.attempts=0
      await this.initialize()
    }finally{this.pairing=false}
  }
  async pruneNonces():Promise<void>{
    const folder=resolve(this.directory,'request-nonces')
    let names:string[]
    try{names=await readdir(folder)}catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')return;throw error}
    for(const name of names){
      if(!/^[a-f0-9]{64}$/.test(name))continue
      const path=resolve(folder,name)
      const timestamp=Number(await readFile(path,'utf8'))
      if(Number.isFinite(timestamp) && Date.now()-timestamp>300_000)await unlink(path)
    }
  }

  async verifyRequest(ownerId: string, token: string, method: string, path: string, body: string,
    timestamp: string, nonce: string, signature: string): Promise<boolean> {
    if (!this.authorize(ownerId,token) || !/^[a-f0-9]{64}$/.test(signature)
        || !/^[a-zA-Z0-9-]{16,100}$/.test(nonce) || !/^\d{13}$/.test(timestamp)
        || Math.abs(Date.now()-Number(timestamp))>90_000) return false
    const data=[method,path,timestamp,nonce,digest(body)].join('\n')
    const expected=createHmac('sha256',token).update(data).digest()
    if(!timingSafeEqual(expected,Buffer.from(signature,'hex'))) return false
    const folder=resolve(this.directory,'request-nonces')
    await mkdir(folder,{recursive:true,mode:0o700})
    try {
      await writeFile(resolve(folder,digest(ownerId+':'+nonce)),timestamp,{flag:'wx',mode:0o600,flush:true})
      return true
    } catch(error) {
      if((error as NodeJS.ErrnoException).code==='EEXIST')return false
      throw error
    }
  }
}
