import type { CalendarItem, Note, Plan, PlanItem, TodoItem } from '../types/planner'

const API_BASE = import.meta.env.VITE_API_BASE_URL
  ?? `${window.location.protocol}//${window.location.hostname}:8081/api`

interface ApiPlan {
  id: string
  title: string
  description?: string | null
  color: string
  status: Plan['status']
  progress: number
  dueDate?: string | null
}

interface ApiSchedule {
  id: string
  title: string
  startAt: string
  durationMinutes: number
  status: CalendarItem['status']
  progress: number
  planId?: string | null
}

interface ApiStage {
  id: string
  title: string
  progress: number
  dueLabel: string
}

interface ApiTodo {
  id: string
  title: string
  dueAt?: string | null
  status: string
  priority: 'high' | 'medium' | 'low'
  reminderMinutes?: number | null
}

interface ApiNote {
  id: string
  title: string
  excerpt: string
  content: string
  sourceType: string
  category?: string | null
}

export interface PlannerStats {
  daily: Array<{ day: string; planned: number; completed: number; minutes: number }>
  heatmap: Array<{ id: string; value: number; label: string; planned?: number; completed?: number; pending?: number }>
  monthly: Array<{ month: string; completion: number; completed: number; delayed: number }>
  metrics: { completion: number; completed: number; planned: number; focusHours: number; streak: number }
}

export interface AiReviewMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface AiPlanChange {
  entity: 'plan' | 'schedule'
  action: 'update'
  id: string
  title: string
  summary: string
  fields: Record<string, string | number | null>
}

export interface AiReviewResponse {
  conversationId: string
  reply: string
  changes: AiPlanChange[]
}

/** 所有 JSON 请求统一经过这里，避免各功能页面重复处理状态码和请求头。 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(API_BASE + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!response.ok) throw new Error(`API ${response.status}: ${path}`)
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

function splitDateTime(value?: string | null) {
  if (!value || value === 'null') return { date: '', time: '' }
  const normalized = value.replace(' ', 'T')
  return { date: normalized.slice(0, 10), time: normalized.slice(11, 16) }
}

function priorityFromApi(value: ApiTodo['priority']): TodoItem['priority'] {
  return value === 'high' ? '高' : value === 'low' ? '低' : '中'
}

function priorityToApi(value: TodoItem['priority']) {
  return value === '高' ? 'high' : value === '低' ? 'low' : 'medium'
}

function todoDueAt(item: TodoItem) {
  return item.date && item.time ? `${item.date}T${item.time}:00` : null
}

export const plannerApi = {
  async load() {
    const [rawPlans, rawSchedules, rawTodos, rawNotes, stats] = await Promise.all([
      request<ApiPlan[]>('/plans'),
      request<ApiSchedule[]>('/schedules'),
      request<ApiTodo[]>('/todos'),
      request<ApiNote[]>('/notes'),
      request<PlannerStats>('/stats'),
    ])
    const stages = await Promise.all(rawPlans.map((item) => request<ApiStage[]>(`/plans/${item.id}/stages`)))
    const relations = await Promise.all(rawNotes.map((item) => request<Array<{ id: string }>>(`/notes/${item.id}/relations`)))
    const plans: Plan[] = rawPlans.map((item, index) => ({
      id: item.id,
      title: item.title,
      subtitle: item.description || '长期计划',
      progress: Math.round(item.progress),
      color: item.color,
      status: item.status,
      completedTasks: stages[index].filter((stage) => stage.progress >= 100).length,
      totalTasks: stages[index].length,
      dueDate: item.dueDate || '',
      items: stages[index].map((stage) => ({ id: stage.id, title: stage.title, progress: Math.round(stage.progress), dueLabel: stage.dueLabel })),
    }))
    const schedules: CalendarItem[] = rawSchedules.map((item) => {
      const when = splitDateTime(item.startAt)
      return { id: item.id, title: item.title, date: when.date, time: when.time, planId: item.planId ?? undefined, color: '#d39a24', status: item.status, duration: item.durationMinutes, progress: Math.round(item.progress) }
    })
    const todos: TodoItem[] = rawTodos.map((item) => {
      const when = splitDateTime(item.dueAt)
      return { id: item.id, title: item.title, date: when.date, time: when.time, priority: priorityFromApi(item.priority), done: item.status === 'done', reminder: item.reminderMinutes == null ? '无提醒' : `提前 ${item.reminderMinutes} 分钟` }
    })
    const notes: Note[] = rawNotes.map((item, index) => ({ id: item.id, title: item.title, category: item.category || '未分类', excerpt: item.excerpt, updatedAt: '刚刚', color: '#d39a24', relatedIds: relations[index].map((relation) => relation.id), source: item.sourceType === 'manual' ? '个人创建' : item.sourceType }))
    return { plans, schedules, todos, notes, stats }
  },

  createPlan: (item: Plan) => request<ApiPlan>('/plans', { method: 'POST', body: JSON.stringify({ title: item.title, description: item.subtitle, color: item.color, status: item.status, progress: item.progress, dueDate: item.dueDate || null }) }),
  updatePlan: (item: Plan) => request<ApiPlan>(`/plans/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, description: item.subtitle, color: item.color, status: item.status, progress: item.progress, dueDate: item.dueDate || null }) }),
  deletePlan: (id: string) => request<void>(`/plans/${id}`, { method: 'DELETE' }),

  createSchedule: (item: CalendarItem) => request<ApiSchedule>('/schedules', { method: 'POST', body: JSON.stringify({ title: item.title, startAt: `${item.date}T${item.time}:00`, durationMinutes: item.duration, status: item.status, progress: item.progress ?? (item.status === 'done' ? 100 : 0), planId: item.planId ?? null }) }),
  updateSchedule: (item: CalendarItem) => request<ApiSchedule>(`/schedules/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, startAt: `${item.date}T${item.time}:00`, durationMinutes: item.duration, status: item.status, progress: item.progress ?? (item.status === 'done' ? 100 : 0), planId: item.planId ?? null }) }),
  deleteSchedule: (id: string) => request<void>(`/schedules/${id}`, { method: 'DELETE' }),

  createTodo: (item: TodoItem) => request<ApiTodo>('/todos', { method: 'POST', body: JSON.stringify({ title: item.title, dueAt: todoDueAt(item), status: item.done ? 'done' : 'pending', priority: priorityToApi(item.priority) }) }),
  updateTodo: (item: TodoItem) => request<ApiTodo>(`/todos/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, dueAt: todoDueAt(item), status: item.done ? 'done' : 'pending', priority: priorityToApi(item.priority) }) }),
  deleteTodo: (id: string) => request<void>(`/todos/${id}`, { method: 'DELETE' }),

  createPlanStage: (planId: string, item: PlanItem) => request<ApiStage>(`/plans/${planId}/stages`, { method: 'POST', body: JSON.stringify({ title: item.title, dueLabel: item.dueLabel, progress: item.progress }) }),
  updatePlanStage: (planId: string, item: PlanItem) => request<ApiStage>(`/plans/${planId}/stages/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, dueLabel: item.dueLabel, progress: item.progress }) }),
  deletePlanStage: (planId: string, id: string) => request<void>(`/plans/${planId}/stages/${id}`, { method: 'DELETE' }),

  createNote: (item: Note) => request<ApiNote>('/notes', { method: 'POST', body: JSON.stringify({ title: item.title, category: item.category, excerpt: item.excerpt, content: item.excerpt, sourceType: 'manual' }) }),
  updateNote: (item: Note) => request<ApiNote>(`/notes/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, category: item.category, excerpt: item.excerpt, content: item.excerpt }) }),
  deleteNote: (id: string) => request<void>(`/notes/${id}`, { method: 'DELETE' }),
  createNoteRelation: (fromNoteId: string, toNoteId: string) => request<void>(`/notes/${fromNoteId}/relations`, { method: 'POST', body: JSON.stringify({ toNoteId }) }),
  deleteNoteRelation: (fromNoteId: string, toNoteId: string) => request<void>(`/notes/${fromNoteId}/relations`, { method: 'DELETE', body: JSON.stringify({ toNoteId }) }),
  downloadExcel: async () => {
    const response = await fetch(API_BASE + '/export/xlsx')
    if (!response.ok) throw new Error(`API ${response.status}: /export/xlsx`)
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a'); link.href = url; link.download = `changlu-plan-${new Date().toISOString().slice(0, 10)}.xlsx`; link.click(); URL.revokeObjectURL(url)
  },
  downloadPdf: async () => {
    const response = await fetch(API_BASE + '/export/pdf')
    if (!response.ok) throw new Error(`API ${response.status}: /export/pdf`)
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a'); link.href = url; link.download = `changlu-plan-statistics-${new Date().toISOString().slice(0, 10)}.pdf`; link.click(); URL.revokeObjectURL(url)
  },
  chatReview: (message: string, history: AiReviewMessage[], conversationId?: string) => request<AiReviewResponse>('/ai/review/chat', {
    method: 'POST',
    body: JSON.stringify({ message, history, conversationId }),
  }),
}
