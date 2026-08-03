/** 计划及阶段的状态只在领域类型中定义，页面和 API 层共享同一套约束。 */
export type PlanStatus = 'active' | 'paused' | 'completed'
export type ItemStatus = 'pending' | 'done' | 'delayed'

export interface PlanItem {
  id: string
  title: string
  progress: number
  dueLabel: string
}

export interface Plan {
  id: string
  title: string
  subtitle: string
  progress: number
  color: string
  status: PlanStatus
  completedTasks: number
  totalTasks: number
  dueDate: string
  items: PlanItem[]
}

export interface CalendarItem {
  id: string
  date: string
  title: string
  time: string
  planId?: string
  color: string
  status: ItemStatus
  duration: number
  progress?: number
  kind?: 'schedule' | 'todo'
  priority?: '高' | '中' | '低'
  reminder?: string
}

export interface TodoItem {
  id: string
  title: string
  date: string
  time: string
  priority: '高' | '中' | '低'
  done: boolean
  reminder: string
}

export interface Note {
  id: string
  title: string
  category: string
  excerpt: string
  updatedAt: string
  color: string
  relatedIds: string[]
  source?: string
}

export interface SourceMaterial {
  id: string
  source: '小红书' | '哔哩哔哩' | '网页'
  title: string
  summary: string
  meta: string
  url: string
  color: string
}
