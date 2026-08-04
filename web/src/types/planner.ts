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
