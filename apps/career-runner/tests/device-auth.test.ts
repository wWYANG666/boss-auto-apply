import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DeviceAuth } from '../src/core/device-auth.js'

test('device pairing is single use, owner bound and survives restart',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'runner-pair-'))
  const auth=new DeviceAuth(directory);await auth.initialize()
  assert.equal(auth.authorize('owner','anything'),false)
  const {code}=JSON.parse(await readFile(join(directory,'pairing.json'),'utf8'))
  await assert.rejects(auth.pair('owner','wrong'))
  const token=await auth.pair('owner',code)
  assert.equal(auth.authorize('owner',token),true)
  assert.equal(auth.authorize('different-owner',token),false)
  assert.equal(auth.authorize('owner',token+'wrong'),false)
  await assert.rejects(auth.pair('owner',code))
  const restored=new DeviceAuth(directory);await restored.initialize()
  assert.equal(restored.authorize('owner',token),true)
  assert.ok(!(await readFile(join(directory,'device-binding.json'),'utf8')).includes(token))
  const replacement='replacement-device-token-12345678901234567890'
  await restored.beginRotation(replacement)
  assert.equal(restored.authorize('owner',token),true)
  assert.equal(restored.authorize('owner',replacement),true)
  const midRotation=new DeviceAuth(directory);await midRotation.initialize()
  await midRotation.beginRotation(replacement)
  await midRotation.commitRotation(replacement)
  await midRotation.commitRotation(replacement)
  assert.equal(midRotation.authorize('owner',token),false)
  assert.equal(midRotation.authorize('owner',replacement),true)
  await midRotation.revoke()
  assert.equal(midRotation.authorize('owner',replacement),false)
  const rePair=JSON.parse(await readFile(join(directory,'pairing.json'),'utf8'))
  assert.ok(rePair.code)
})
