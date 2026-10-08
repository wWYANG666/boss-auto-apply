import { mkdir, readFile, open } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import { resolve } from 'node:path'

export async function localApprovalSecret(directory:string): Promise<string> {
  await mkdir(directory,{recursive:true,mode:0o700})
  const path=resolve(directory,'approval-secret')
  try {return (await readFile(path,'utf8')).trim()}
  catch(error){if((error as NodeJS.ErrnoException).code!=='ENOENT')throw error}
  const secret=randomBytes(32).toString('hex')
  try {
    const file=await open(path,'wx',0o600)
    try {await file.writeFile(secret);await file.sync()}finally{await file.close()}
    return secret
  } catch(error) {
    if((error as NodeJS.ErrnoException).code==='EEXIST')return (await readFile(path,'utf8')).trim()
    throw error
  }
}
