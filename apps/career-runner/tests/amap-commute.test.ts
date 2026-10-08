import assert from 'node:assert/strict'
import { test } from 'node:test'
import { straightDistanceKm } from '../src/adapters/amap-commute.js'

test('straight commute distance uses coordinates in longitude-latitude order',()=>{
  const km=straightDistanceKm({longitude:118.7969,latitude:32.0603},{longitude:118.8069,latitude:32.0603})
  assert.ok(km>0.8&&km<1.1)
})
