/** 计划及阶段的状态只在领域类型中定义，页面和 API 层共享同一套约束。 */
export type PlanStatus = 'active' | 'paused' | 'completed'
export type ItemStatus = 'pending' | 'done' | 'delayed'
export type PlanTaskStatus = 'pending' | 'in_progress' | 'done' | 'blocked' | 'skipped' | 'cancelled'
export type TaskRecurrenceType = 'once' | 'daily' | 'every_other_day' | 'weekdays' | 'weekly'

export interface PlanTask {
  id: string
  planId: string
  stageId: string
  title: string
  description?: string
  status: PlanTaskStatus
  priority: 'high' | 'medium' | 'low'
  estimatedMinutes?: number
  actualMinutes?: number
  dueAt?: string
  recurrenceType: TaskRecurrenceType
  scheduleStartDate?: string
  recurrenceEndDate?: string
  scheduledTime?: string
  scheduleCount: number
  completedScheduleCount: number
  scheduleProgress: number
  reason?: string
  version: number
}

export interface PlanItem {
  id: string
  title: string
  progress: number
  dueLabel: string
  taskProgress: number
  effortProgress: number
  version: number
  tasks: PlanTask[]
}

export interface Plan {
  id: string
  title: string
  subtitle: string
  progress: number
  taskProgress: number
  effortProgress: number
  color: string
  status: PlanStatus
  completedTasks: number
  totalTasks: number
  dueDate: string
  items: PlanItem[]
  version: number
}

export interface CalendarItem {
  id: string
  date: string
  title: string
  time: string
  planId?: string
  stageId?: string
  taskId?: string
  color: string
  status: ItemStatus
  duration: number
  progress?: number
  kind?: 'schedule' | 'todo'
  priority?: '高' | '中' | '低'
  reminder?: string
  version?: number
  locationName?: string
  latitude?: number
  longitude?: number
  coordinateSystem?: string
  timezoneId?: string
  sourceUrl?: string
  reservationRequired?: boolean | null
}

export interface TravelTransit {
  mode?: string
  durationMinutes?: number
  durationSeconds?: number
  distanceMeters?: number
  verificationRequired?: boolean
}

export interface TravelActivity {
  attractionId?: string
  attractionName?: string
  title?: string
  location?: string
  startTime?: string
  durationMinutes?: number
  indoor?: boolean
  requiresReservation?: boolean | null
  openingHours?: string
  transitFromPrevious?: TravelTransit
  backupActivity?: { title?: string; attractionName?: string; reason?: string }
}

export interface TravelDay {
  date?: string
  title?: string
  activities?: TravelActivity[]
}

export interface TravelWeather {
  date?: string
  condition?: string
  tempHigh?: number | null
  tempLow?: number | null
  precipitationProbability?: number | null
  warnings?: string[]
  forecastConfidence?: 'high' | 'medium' | 'low'
  provider?: string
  fetchedAt?: string
}

export interface TravelPlanData extends Record<string, unknown> {
  request?: Record<string, unknown> & { destination?: string; startDate?: string; endDate?: string }
  days?: TravelDay[]
  weather?: TravelWeather[]
  budgetEstimate?: {
    amount?: number; minimum?: number; maximum?: number; currency?: string
    confidence?: 'high' | 'medium' | 'low'; estimated?: boolean
    breakdown?: Array<{ category?: string; amount?: number; minimum?: number; maximum?: number; source?: string }>
  }
  sources?: Array<{ provider?: string; title?: string; sourceUrl?: string; url?: string; fetchedAt?: string; confidence?: string; sourceQuality?: string }>
  risks?: Array<{ code?: string; message?: string; date?: string; detail?: string; verificationRequired?: boolean } | string>
  alternativePlans?: Array<{ title?: string; reason?: string }>
  revisionMode?: 'localized'
  revisionSummary?: string
  revisionDiff?: Array<{ date?: string; day?: number; activity?: number; before?: TravelActivity; after?: TravelActivity }>
}

export interface TodoItem {
  id: string
  title: string
  date: string
  time: string
  priority: '高' | '中' | '低'
  done: boolean
  reminder: string
  version?: number
}

export interface Note {
  id: string
  title: string
  category: string
  excerpt: string
  content?: string
  updatedAt: string
  color: string
  relatedIds: string[]
  source?: string
}

export interface SourceMaterial {
  id: string
  kind?: 'platform' | 'web'
  source: string
  title: string
  summary: string
  meta: string
  url: string
  color: string
}

export interface ScheduleMaterialsResponse {
  query: string
  materials: SourceMaterial[]
  keyPoints: string[]
  studyNote: string
  sections: Array<{ title: string; content: string }>
  aiGenerated: boolean
}
