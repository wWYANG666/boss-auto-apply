import type { NormalizedJob, SearchSpec } from '../domain.js'

type Coordinates={longitude:number;latitude:number}
type TimedValue<T>={expiresAt:number;value:T}

const geocodeCache=new Map<string,TimedValue<Coordinates>>()
const routeCache=new Map<string,TimedValue<{distanceKm:number;durationMinutes:number}>>()
const CACHE_TTL_MS=60*60_000

function valid(point:Coordinates):boolean{
  return Number.isFinite(point.longitude)&&Number.isFinite(point.latitude)&&Math.abs(point.longitude)<=180&&Math.abs(point.latitude)<=90
}

export function straightDistanceKm(origin:Coordinates,destination:Coordinates):number{
  const radians=(value:number)=>value*Math.PI/180
  const earth=6371
  const dLat=radians(destination.latitude-origin.latitude)
  const dLon=radians(destination.longitude-origin.longitude)
  const a=Math.sin(dLat/2)**2+Math.cos(radians(origin.latitude))*Math.cos(radians(destination.latitude))*Math.sin(dLon/2)**2
  return earth*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a))
}

async function amapJson(url:URL):Promise<Record<string,unknown>>{
  const response=await fetch(url,{signal:AbortSignal.timeout(8_000)})
  if(!response.ok)throw new Error(`AMAP_HTTP_${response.status}`)
  const payload=await response.json() as Record<string,unknown>
  if(String(payload.status)!=='1')throw new Error(`AMAP_ERROR: ${String(payload.info??'请求失败')}`)
  return payload
}

async function geocode(address:string,key:string):Promise<Coordinates>{
  const cached=geocodeCache.get(address)
  if(cached&&cached.expiresAt>Date.now())return cached.value
  const url=new URL('https://restapi.amap.com/v3/geocode/geo')
  url.search=new URLSearchParams({key,address,output:'JSON'}).toString()
  const payload=await amapJson(url)
  const first=Array.isArray(payload.geocodes)?payload.geocodes[0] as Record<string,unknown>|undefined:undefined
  const parts=String(first?.location??'').split(',').map(Number)
  const longitude=parts[0]??Number.NaN,latitude=parts[1]??Number.NaN
  const point={longitude,latitude}
  if(!valid(point))throw new Error('AMAP_GEOCODE_EMPTY: 无法识别通勤起点地址')
  geocodeCache.set(address,{expiresAt:Date.now()+CACHE_TTL_MS,value:point})
  return point
}

async function route(origin:Coordinates,destination:Coordinates,key:string,mode:'driving'|'walking'){
  const cacheKey=`${mode}:${origin.longitude.toFixed(5)},${origin.latitude.toFixed(5)}:${destination.longitude.toFixed(5)},${destination.latitude.toFixed(5)}`
  const cached=routeCache.get(cacheKey)
  if(cached&&cached.expiresAt>Date.now())return cached.value
  const path=mode==='driving'?'/v3/direction/driving':'/v3/direction/walking'
  const url=new URL(`https://restapi.amap.com${path}`)
  url.search=new URLSearchParams({key,origin:`${origin.longitude.toFixed(6)},${origin.latitude.toFixed(6)}`,
    destination:`${destination.longitude.toFixed(6)},${destination.latitude.toFixed(6)}`,output:'JSON',...(mode==='driving'?{strategy:'10'}:{})}).toString()
  const payload=await amapJson(url)
  const route=payload.route as Record<string,unknown>|undefined
  const first=Array.isArray(route?.paths)?route.paths[0] as Record<string,unknown>|undefined:undefined
  const distance=Number(first?.distance),duration=Number(first?.duration)
  if(!Number.isFinite(distance)||!Number.isFinite(duration))throw new Error('AMAP_ROUTE_EMPTY: 未返回通勤路线')
  const value={distanceKm:distance/1000,durationMinutes:duration/60}
  if(routeCache.size>=5_000)routeCache.clear()
  routeCache.set(cacheKey,{expiresAt:Date.now()+CACHE_TTL_MS,value})
  return value
}

async function mapConcurrent<T,R>(items:T[],limit:number,worker:(item:T)=>Promise<R>):Promise<R[]>{
  const results=new Array<R>(items.length)
  let cursor=0
  await Promise.all(Array.from({length:Math.min(limit,items.length)},async()=>{
    while(cursor<items.length){
      const index=cursor++
      results[index]=await worker(items[index]!)
    }
  }))
  return results
}

export async function enrichCommute(jobs:NormalizedJob[],spec:SearchSpec):Promise<NormalizedJob[]>{
  const commuteMode=spec.commuteMode
  if(commuteMode==='none'||!spec.homeAddress.trim())return jobs
  const key=(process.env.AMAP_WEB_SERVICE_KEY??'').trim()
  if(!key)throw new Error('AMAP_KEY_REQUIRED: 已启用通勤筛选，请在Runner配置AMAP_WEB_SERVICE_KEY')
  const origin=await geocode(spec.homeAddress,key)
  const enrich=async(job:NormalizedJob):Promise<NormalizedJob|null>=>{
    if(job.longitude==null||job.latitude==null)return job
    const destination={longitude:job.longitude,latitude:job.latitude}
    const metric=commuteMode==='straight'
      ?{distanceKm:straightDistanceKm(origin,destination),durationMinutes:undefined}
      :await route(origin,destination,key,commuteMode).catch(()=>null)
    if(!metric)return job
    const value={...job,commuteDistanceKm:Math.round(metric.distanceKm*10)/10,
      commuteDurationMinutes:metric.durationMinutes==null?undefined:Math.round(metric.durationMinutes)}
    if(spec.maxCommuteDistanceKm>0&&value.commuteDistanceKm>spec.maxCommuteDistanceKm)return null
    if(spec.maxCommuteMinutes>0&&value.commuteDurationMinutes!=null&&value.commuteDurationMinutes>spec.maxCommuteMinutes)return null
    return value
  }
  const enriched=commuteMode==='straight'
    ?await mapConcurrent(jobs,1,enrich)
    :await mapConcurrent(jobs,4,enrich)
  return enriched.filter((job):job is NormalizedJob=>job!==null)
}
