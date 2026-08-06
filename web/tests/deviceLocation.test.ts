import assert from 'node:assert/strict'
import test from 'node:test'
import { collectDeviceLocation, isTravelRequest, type GeolocationLike } from '../src/features/agent/deviceLocation.js'

const fixedNow = () => new Date('2026-08-05T10:00:00.000Z')

test('collects an authorized browser location without changing its precision', async () => {
  const geolocation: GeolocationLike = {
    getCurrentPosition(success) {
      success({ coords: { latitude: 36.0671, longitude: 120.3826, accuracy: 24 } })
    },
  }
  assert.deepEqual(await collectDeviceLocation({ geolocation, timezone: 'Asia/Shanghai', now: fixedNow }), {
    lat: 36.0671,
    lng: 120.3826,
    accuracyMeters: 24,
    timezone: 'Asia/Shanghai',
    capturedAt: '2026-08-05T10:00:00.000Z',
    permission: 'granted',
  })
})

test('reports denied permission and keeps the request usable', async () => {
  const geolocation: GeolocationLike = {
    getCurrentPosition(_success, error) { error({ code: 1 }) },
  }
  const result = await collectDeviceLocation({ geolocation, timezone: 'Asia/Shanghai', now: fixedNow })
  assert.equal(result.permission, 'denied')
  assert.equal(result.lat, undefined)
})

test('returns unavailable after the five second deadline', async () => {
  const geolocation: GeolocationLike = { getCurrentPosition() {} }
  const result = await collectDeviceLocation({ geolocation, timeoutMs: 5, now: fixedNow })
  assert.equal(result.permission, 'unavailable')
})

test('detects travel requests but does not locate again for approval', () => {
  assert.equal(isTravelRequest('明天去青岛旅游十天，喜欢海边'), true)
  assert.equal(isTravelRequest('请结合信息搜集表生成旅行计划。', { destination: '青岛' }), true)
  assert.equal(isTravelRequest('确认行程，生成写入计划和日历草案'), false)
})
