import { mkdir, readFile, rm, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import type { RunnerTask } from '../domain.js'

interface JournalRecord { kind:'task'; at:string; task:RunnerTask }

/** Durable Runner state backed by SQLite with one-time JSONL import. */
export class JsonlJournal {
  private readonly databasePath:string
  private readonly legacyPath:string
  private database?:DatabaseSync
  private writeChain:Promise<void>=Promise.resolve()

  constructor(dataDirectory:string){
    this.databasePath=resolve(dataDirectory,'runner-journal.sqlite')
    this.legacyPath=resolve(dataDirectory,'journal.jsonl')
  }

  private async db():Promise<DatabaseSync>{
    if(this.database)return this.database
    await mkdir(dirname(this.databasePath),{recursive:true,mode:0o700})
    const database=new DatabaseSync(this.databasePath)
    database.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;')
    database.exec(`CREATE TABLE IF NOT EXISTS runner_task (
      command_id TEXT PRIMARY KEY, task_id TEXT NOT NULL, status TEXT NOT NULL,
      task_json TEXT NOT NULL, updated_at TEXT NOT NULL
    ); CREATE INDEX IF NOT EXISTS idx_runner_task_status ON runner_task(status,updated_at);`)
    database.exec("DELETE FROM runner_task WHERE status IN ('succeeded','failed','cancelled') AND julianday(updated_at)<julianday('now','-180 days')")
    database.exec('PRAGMA wal_checkpoint(TRUNCATE)')
    this.database=database
    return database
  }

  async append(task:RunnerTask):Promise<void>{
    const snapshot=structuredClone(task)
    const work=this.writeChain.then(async()=>{
      const database=await this.db()
      database.prepare(`INSERT INTO runner_task(command_id,task_id,status,task_json,updated_at)
        VALUES (?,?,?,?,?) ON CONFLICT(command_id) DO UPDATE SET
        task_id=excluded.task_id,status=excluded.status,task_json=excluded.task_json,updated_at=excluded.updated_at`)
        .run(snapshot.commandId,snapshot.id,snapshot.status,JSON.stringify(snapshot),new Date().toISOString())
    })
    this.writeChain=work.catch(()=>undefined)
    await work
  }

  async loadLatest():Promise<Map<string,RunnerTask>>{
    const database=await this.db()
    const rows=database.prepare('SELECT task_json FROM runner_task ORDER BY updated_at').all() as Array<{task_json:string}>
    if(rows.length)return this.parseRows(rows.map(row=>row.task_json))
    const legacy=await this.loadLegacy()
    if(!legacy.size)return legacy
    const insert=database.prepare(`INSERT INTO runner_task(command_id,task_id,status,task_json,updated_at)
      VALUES (?,?,?,?,?) ON CONFLICT(command_id) DO UPDATE SET task_json=excluded.task_json,status=excluded.status,updated_at=excluded.updated_at`)
    database.exec('BEGIN IMMEDIATE')
    try{
      for(const task of legacy.values())insert.run(task.commandId,task.id,task.status,JSON.stringify(task),new Date().toISOString())
      database.exec('COMMIT');database.exec('PRAGMA wal_checkpoint(TRUNCATE)')
    }catch(error){database.exec('ROLLBACK');throw error}
    return legacy
  }

  async statistics(){
    const database=await this.db()
    const total=(database.prepare('SELECT COUNT(*) AS count FROM runner_task').get() as {count:number}).count
    const active=(database.prepare("SELECT COUNT(*) AS count FROM runner_task WHERE status IN ('queued','running','waiting_human','unknown_outcome')").get() as {count:number}).count
    const databaseBytes=await stat(this.databasePath).then(value=>value.size).catch(()=>0)
    const legacyBytes=await stat(this.legacyPath).then(value=>value.size).catch(()=>0)
    return{databaseBytes,legacyBytes,totalTasks:total,activeTasks:active,retentionDays:180}
  }

  async cleanupLegacy(){
    const current=await this.statistics()
    if(current.totalTasks===0)throw new Error('SQLITE_MIGRATION_NOT_VERIFIED')
    if(current.activeTasks>0)throw new Error('RUNNER_ACTIVE_TASKS_EXIST')
    await rm(this.legacyPath,{force:true})
    return{deletedBytes:current.legacyBytes}
  }

  private parseRows(rows:string[]):Map<string,RunnerTask>{
    const tasks=new Map<string,RunnerTask>()
    for(const value of rows){
      try{const task=JSON.parse(value) as RunnerTask;if(task?.commandId)tasks.set(task.commandId,task)}catch{/* Ignore damaged rows. */}
    }
    return tasks
  }

  private async loadLegacy():Promise<Map<string,RunnerTask>>{
    let content=''
    try{content=await readFile(this.legacyPath,'utf8')}
    catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')return new Map();throw error}
    const tasks=new Map<string,RunnerTask>()
    for(const line of content.split(/\r?\n/)){
      if(!line.trim())continue
      try{const record=JSON.parse(line) as JournalRecord;if(record.kind==='task'&&record.task?.commandId)tasks.set(record.task.commandId,record.task)}
      catch{/* A truncated final line does not invalidate previous records. */}
    }
    return tasks
  }
}
