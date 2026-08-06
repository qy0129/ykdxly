export type DeviceLocationPermission = 'granted' | 'denied' | 'unavailable'

export interface DeviceLocationContext {
  lat?: number
  lng?: number
  accuracyMeters?: number
  timezone: string
  capturedAt: string
  permission: DeviceLocationPermission
}

interface PositionLike {
  coords: { latitude: number; longitude: number; accuracy: number }
}

interface PositionErrorLike {
  code: number
}

export interface GeolocationLike {
  getCurrentPosition(
    success: (position: PositionLike) => void,
    error: (error: PositionErrorLike) => void,
    options: PositionOptions,
  ): void
}

interface LocationOptions {
  geolocation?: GeolocationLike
  timezone?: string
  timeoutMs?: number
  now?: () => Date
}

const TRAVEL_WORDS = /(旅游|旅行|行程|出游|度假|景点|攻略|海边|沙滩)/
const TRAVEL_ROUTE = /(明天|后天|下周|\d+\s*[天日]|[一二三四五六七八九十两]+天).{0,12}(去|到).{1,16}(玩|游|住)/
const APPROVAL_ONLY = /^(确认|同意|按这个).*(行程|方案|草案)/

export function isTravelRequest(message: string, argumentsValue?: Record<string, unknown>) {
  if (APPROVAL_ONLY.test(message.trim())) return false
  if (typeof argumentsValue?.destination === 'string' && argumentsValue.destination.trim()) return true
  return TRAVEL_WORDS.test(message) || TRAVEL_ROUTE.test(message)
}

export async function collectDeviceLocation(options: LocationOptions = {}): Promise<DeviceLocationContext> {
  const now = options.now ?? (() => new Date())
  const timezone = options.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'Asia/Shanghai'
  const geolocation = options.geolocation ?? globalThis.navigator?.geolocation
  const timeoutMs = options.timeoutMs ?? 5000
  const unavailable = (): DeviceLocationContext => ({
    timezone,
    capturedAt: now().toISOString(),
    permission: 'unavailable',
  })
  if (!geolocation) return unavailable()

  return new Promise((resolve) => {
    let settled = false
    const finish = (value: DeviceLocationContext) => {
      if (settled) return
      settled = true
      globalThis.clearTimeout(timer)
      resolve(value)
    }
    const timer = globalThis.setTimeout(() => finish(unavailable()), timeoutMs)
    geolocation.getCurrentPosition(
      (position) => finish({
        lat: position.coords.latitude,
        lng: position.coords.longitude,
        accuracyMeters: position.coords.accuracy,
        timezone,
        capturedAt: now().toISOString(),
        permission: 'granted',
      }),
      (error) => finish({
        timezone,
        capturedAt: now().toISOString(),
        permission: error.code === 1 ? 'denied' : 'unavailable',
      }),
      { enableHighAccuracy: false, maximumAge: 10 * 60 * 1000, timeout: timeoutMs },
    )
  })
}
