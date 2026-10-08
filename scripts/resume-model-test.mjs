import assert from 'node:assert/strict'
import { normalizeResume } from '../src/domain/resume.ts'
const old={profile:{name:'User'},sections:[
 {type:'PROJECT',elementId:'one',title:'First',highlights:['A'],metadata:{keep:true}},
 {type:'PROJECT',elementId:'two',title:'Second',hidden:true},
 {type:'EDUCATION',school:'School'}],sourceDocument:{rawText:'original'}}
const converted=normalizeResume(old)
assert.equal(converted.sections.length,3)
assert.equal(converted.sections[0].items[0].title,'First')
assert.equal(converted.sections[0].title,undefined)
assert.equal(converted.sections[1].hidden,true)
assert.deepEqual(normalizeResume(converted),converted)
assert.deepEqual(converted.sourceDocument,old.sourceDocument)
const exchange=normalizeResume({basics:{name:'User',label:'Developer'},work:[{name:'Employer',position:'Dev'}],skills:[{name:'Java',keywords:['JUnit']}]})
assert.equal(exchange.profile.headline,'Developer')
assert.equal(exchange.sections[0].items[0].company,'Employer')
assert.deepEqual(exchange.sections[1].items,['Java','JUnit'])
console.log('Resume migration and JSON Resume conversion passed')
