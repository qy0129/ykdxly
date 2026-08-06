import type { CalendarItem, Note, Plan, PlanItem, PlanTask, ScheduleMaterialsResponse, TaskRecurrenceType, TodoItem, TravelPlanData } from '../types/planner'

const DEV_PORTS = new Set(['4173', '5173', '5187'])
const API_BASE = import.meta.env.VITE_API_BASE_URL
  ?? (DEV_PORTS.has(window.location.port) ? '/api' : `${window.location.origin}/api`)

interface ApiPlan {
  id: string
  title: string
  description?: string | null
  color: string
  status: Plan['status']
  progress: number
  taskProgress: number
  effortProgress: number
  version: number
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
  stageId?: string | null
  taskId?: string | null
  version: number
  locationName?: string | null
  latitude?: number | null
  longitude?: number | null
  coordinateSystem?: string | null
  timezoneId?: string | null
  sourceUrl?: string | null
  reservationRequired?: boolean | null
}

interface ApiStage {
  id: string
  title: string
  progress: number
  taskProgress: number
  effortProgress: number
  dueLabel: string
  version: number
}

interface ApiTask {
  id: string
  planId: string
  stageId: string
  title: string
  description?: string | null
  status: PlanTask['status']
  priority: PlanTask['priority']
  estimatedMinutes?: number | null
  actualMinutes?: number | null
  dueAt?: string | null
  recurrenceType: TaskRecurrenceType
  scheduleStartDate?: string | null
  recurrenceEndDate?: string | null
  scheduledTime?: string | null
  scheduleCount: number
  completedScheduleCount: number
  scheduleProgress: number
  reason?: string | null
  version: number
}

interface ApiTodo {
  id: string
  title: string
  dueAt?: string | null
  status: string
  priority: 'high' | 'medium' | 'low'
  reminderMinutes?: number | null
  version: number
}

export interface TodoReminder {
  id: string
  todoId: string
  title: string
  dueAt: string
  reminderMinutes: number
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

export interface NoteChatTurn {
  role: 'user' | 'assistant'
  content: string
}

export interface NoteGenerateContext {
  scheduleTitle: string
  studyNote: string
  draftNote: string
  keyPoints: string[]
  sections: Array<{ title: string; content: string }>
  message: string
  history: NoteChatTurn[]
}

export interface NoteGenerateResponse {
  markdown: string
  reply: string
  title: string
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

export interface AiDraftAction {
  type: string
  summary: string
  targetId?: string
  fields: Record<string, unknown>
  changes?: Array<{ field: string; before: unknown; after: unknown }>
}

export interface AiDraft {
  id: string
  code: string
  reply: string
  status: 'pending' | 'confirmed' | 'cancelled' | 'expired'
  expiresAt: string
  actions: AiDraftAction[]
}

export interface AiCommandResponse {
  conversationId: string
  reply: string
  questions: string[]
  actions: AiDraftAction[]
  draft?: AiDraft
}

export type AgentRunStatus = 'RUNNING' | 'WAITING_USER' | 'WAITING_CONFIRMATION' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface AgentInputRequirement {
  field: string
  label: string
  type: 'text' | 'date' | 'number' | 'select' | 'textarea'
  required?: boolean
  options?: string[]
}

/** Agent 主循环中的一个步骤：主执行器步骤或 subagent 内部的工具子步骤。 */
export interface AgentRunStep {
  seq: number
  parentStepId?: string
  stepLevel: 'main' | 'tool'
  executorType: string
  executorName: string
  label: string
  message?: string
  status: string
  result?: unknown
  toolCallId?: string
  durationMs?: number
  startedAt: string
  completedAt?: string
}

export interface AgentToolCall {
  toolCallId: string
  executorType?: string
  toolName?: string
  arguments?: unknown
  result?: unknown
  status: string
  requiresConfirmation?: boolean
  attemptCount?: number
  error?: string
}

export interface AgentRunResponse {
  runId: string
  conversationId: string
  status: AgentRunStatus
  iteration: number
  reply?: string
  questions?: string[]
  actions?: AiDraftAction[]
  draft?: AiDraft
  executorType?: 'tool' | 'subagent'
  executorName?: string
  lastError?: string
  report?: ReviewReport
  steps?: AgentRunStep[]
  toolCalls?: AgentToolCall[]
  data?: Record<string, unknown>
  planReview?: boolean
  travelData?: TravelPlanData
  inputRequirements?: AgentInputRequirement[]
  formTitle?: string
  imageUrl?: string
  images?: Array<{ imageUrl?: string; requestId?: string }>
}

export interface AgentDocument {
  id: string
  fileName: string
  extension: string
  extractedChars: number
  chunkCount: number
  vectorIndexed: boolean
  duplicate: boolean
  preview: string
}

export interface AiSession {
  conversationId?: string
  runId?: string
  runStatus?: AgentRunResponse['status']
  messages: Array<{ role: 'user' | 'assistant'; content: string; imageUrls?: string[]; createdAt: string }>
  draft?: AiDraft
  planReview?: boolean
  travelData?: TravelPlanData
  inputRequirements?: AgentInputRequirement[]
  formTitle?: string
}

export interface AiConversation {
  id: string
  title: string
  sourceChannel: string
  lastMessage?: string | null
  messageCount: number
  hasPendingDraft: boolean
  runId?: string
  runStatus?: AgentRunStatus
  createdAt: string
  updatedAt: string
}

export interface AiConversationDetail extends AiSession {
  conversationId: string
  title: string
  sourceChannel: string
  contextSummary?: string | null
  createdAt: string
  updatedAt: string
}

export interface AiMemory {
  id: string
  key: string
  category: 'preference' | 'personality' | 'communication_style' | 'long_term_goal' | 'constraint' | 'personal_fact'
  content: string
  createdAt: string
  updatedAt: string
}

export interface PlanningPreference {
  configured: boolean
  timezone: string
  availability?: Record<string, Array<{ start: string; end: string }>>
  maxSessionMinutes?: number
  bufferMinutes?: number
}

export interface ReviewFacts {
  date: string
  completed: number
  completedTasks: number
  scheduleCompleted: number
  delayed: number
  focusMinutes: number
  blocked: number
  estimationError7d: number
  logs: Array<{ entityType: string; action: string; note: string; actualMinutes?: number; occurredAt: string }>
  recentExecution: Array<{ entityType: string; action: string; reason: string; actualMinutes?: number; occurredAt: string }>
}

export interface ReviewReport {
  date: string
  facts: ReviewFacts
  summary: string
  highlights: string[]
  risks: string[]
  nextActions: string[]
  generatedAt: string
  aiGenerated: boolean
}

/**
 * 兼容旧版本缓存：旧后端曾把 AI 返回的完整 JSON 写进 summary 字段。
 * 页面只消费统一的结构化字段，避免把 JSON 原文直接展示给用户。
 */
function normalizeReviewReport(report: ReviewReport): ReviewReport {
  let value: unknown = report.summary?.trim()
  for (let attempt = 0; attempt < 2 && typeof value === 'string'; attempt += 1) {
    const source = value.trim()
    if (!source.startsWith('{') || !source.endsWith('}')) break
    try { value = JSON.parse(source) } catch { break }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return report

  const generated = value as Record<string, unknown>
  const summary = typeof generated.summary === 'string' ? generated.summary.trim() : ''
  if (!summary) return report
  const strings = (candidate: unknown, fallback: string[]) =>
    Array.isArray(candidate) ? candidate.filter((item): item is string => typeof item === 'string') : fallback
  return {
    ...report,
    summary,
    highlights: strings(generated.highlights, report.highlights),
    risks: strings(generated.risks, report.risks),
    nextActions: strings(generated.nextActions, report.nextActions),
  }
}

export interface UserProfile {
  displayName: string
  avatarUrl: string
}

export interface TrashItem {
  id: string
  type: 'plan' | 'stage' | 'task' | 'todo' | 'schedule'
  title: string
  deletedAt: string
  purgeAfter?: string | null
}

/** 所有 JSON 请求统一经过这里，避免各功能页面重复处理状态码和请求头。 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(API_BASE + path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
  } catch (cause) {
    const detail = cause instanceof Error && cause.message !== 'Failed to fetch' ? `（${cause.message}）` : ''
    throw new Error(`无法连接后端服务，请确认服务已启动${detail}`)
  }
  if (!response.ok) {
    let payload: { message?: string; error?: string } | undefined
    try { payload = await response.json() as { message?: string; error?: string } }
    catch { payload = undefined }
    throw new Error(payload?.message || payload?.error || `请求失败（${response.status}）`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

function requireArray<T>(value: unknown, path: string): T[] {
  if (!Array.isArray(value)) throw new Error(`${path} 返回格式错误：预期数组`)
  return value as T[]
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

/** 将界面上的提醒选项转换为数据库使用的分钟数。null 表示不提醒。 */
function reminderToMinutes(value: TodoItem['reminder']) {
  if (value === '到点提醒') return 0
  if (value === '提前 30 分钟') return 30
  if (value === '提前 2 小时') return 120
  if (value === '提前 1 天') return 1440
  return null
}

/** 将数据库分钟数还原为界面文案，避免到点提醒显示成“提前 0 分钟”。 */
function reminderFromMinutes(value: ApiTodo['reminderMinutes']): TodoItem['reminder'] {
  if (value === 0) return '到点提醒'
  if (value === 30) return '提前 30 分钟'
  if (value === 120) return '提前 2 小时'
  if (value === 1440) return '提前 1 天'
  return '无提醒'
}

const NOTE_GRAPH_COLORS = ['#7c647d', '#d39a24', '#b85f42', '#72806a']

function noteGraphColor(category?: string | null, sourceType?: string) {
  const key = `${category ?? ''}:${sourceType ?? ''}`.trim()
  if (!key) return NOTE_GRAPH_COLORS[0]
  if (key.includes('学习') || key.includes('阅读')) return NOTE_GRAPH_COLORS[0]
  if (key.includes('计划') || key.includes('方法')) return NOTE_GRAPH_COLORS[1]
  if (key.includes('产品') || key.includes('收藏')) return NOTE_GRAPH_COLORS[2]
  if (key.includes('复盘') || key.includes('总结')) return NOTE_GRAPH_COLORS[3]
  const score = Array.from(key).reduce((total, character) => total + (character.codePointAt(0) ?? 0), 0)
  return NOTE_GRAPH_COLORS[score % NOTE_GRAPH_COLORS.length]
}

function todoDueAt(item: TodoItem) {
  const time = item.time === '未安排' ? '' : item.time
  return item.date && time ? `${item.date}T${time}:00` : null
}

export const plannerApi = {
  async load() {
    const [plansValue, schedulesValue, todosValue, notesValue, trashValue, stats] = await Promise.all([
      request<unknown>('/plans'),
      request<unknown>('/schedules'),
      request<unknown>('/todos'),
      request<unknown>('/notes'),
      request<unknown>('/trash'),
      request<PlannerStats>('/stats'),
    ])
    const rawPlans = requireArray<ApiPlan>(plansValue, '/plans')
    const rawSchedules = requireArray<ApiSchedule>(schedulesValue, '/schedules')
    const rawTodos = requireArray<ApiTodo>(todosValue, '/todos')
    const rawNotes = requireArray<ApiNote>(notesValue, '/notes')
    const trash = requireArray<TrashItem>(trashValue, '/trash')
    const [stages, tasks] = await Promise.all([
      Promise.all(rawPlans.map(async (item) => requireArray<ApiStage>(await request<unknown>(`/plans/${item.id}/stages`), `/plans/${item.id}/stages`))),
      Promise.all(rawPlans.map(async (item) => requireArray<ApiTask>(await request<unknown>(`/plans/${item.id}/tasks`), `/plans/${item.id}/tasks`))),
    ])
    const relations = await Promise.all(rawNotes.map((item) => request<Array<{ id: string }>>(`/notes/${item.id}/relations`)))
    const plans: Plan[] = rawPlans.map((item, index) => ({
      id: item.id,
      title: item.title,
      subtitle: item.description || '长期计划',
      progress: Math.round(item.progress),
      taskProgress: Math.round(item.taskProgress),
      effortProgress: Math.round(item.effortProgress),
      color: item.color,
      status: item.status,
      completedTasks: tasks[index].filter((task) => task.status === 'done').length,
      totalTasks: tasks[index].filter((task) => task.status !== 'cancelled').length,
      dueDate: item.dueDate || '',
      version: item.version,
      items: stages[index].map((stage) => ({
        id: stage.id, title: stage.title, progress: Math.round(stage.progress), taskProgress: Math.round(stage.taskProgress),
        effortProgress: Math.round(stage.effortProgress), dueLabel: stage.dueLabel, version: stage.version,
        tasks: tasks[index].filter((task) => task.stageId === stage.id).map((task) => ({
          ...task,
          description: task.description ?? undefined,
          estimatedMinutes: task.estimatedMinutes ?? undefined,
          actualMinutes: task.actualMinutes ?? undefined,
          dueAt: task.dueAt ?? undefined,
          scheduleStartDate: task.scheduleStartDate ?? undefined,
          recurrenceEndDate: task.recurrenceEndDate ?? undefined,
          scheduledTime: task.scheduledTime?.slice(0, 5) ?? undefined,
          reason: task.reason ?? undefined,
        })),
      })),
    }))
    const schedules: CalendarItem[] = rawSchedules.map((item) => {
      const when = splitDateTime(item.startAt)
      return {
        id: item.id, title: item.title, date: when.date, time: when.time,
        planId: item.planId ?? undefined, stageId: item.stageId ?? undefined, taskId: item.taskId ?? undefined,
        color: '#d39a24', status: item.status, duration: item.durationMinutes,
        progress: Math.round(item.progress), version: item.version,
        locationName: item.locationName ?? undefined,
        latitude: item.latitude ?? undefined,
        longitude: item.longitude ?? undefined,
        coordinateSystem: item.coordinateSystem ?? undefined,
        timezoneId: item.timezoneId ?? undefined,
        sourceUrl: item.sourceUrl ?? undefined,
        reservationRequired: item.reservationRequired,
      }
    })
    const todos: TodoItem[] = rawTodos.map((item) => {
      const when = splitDateTime(item.dueAt)
      return { id: item.id, title: item.title, date: when.date, time: when.time, priority: priorityFromApi(item.priority), done: item.status === 'done', reminder: reminderFromMinutes(item.reminderMinutes), version: item.version }
    })
    const notes: Note[] = rawNotes.map((item, index) => ({ id: item.id, title: item.title, category: item.category || '未分类', excerpt: item.excerpt || item.content, content: item.content || item.excerpt, updatedAt: '刚刚', color: noteGraphColor(item.category, item.sourceType), relatedIds: relations[index].map((relation) => relation.id), source: item.sourceType === 'manual' ? '个人创建' : item.sourceType }))
    return { plans, schedules, todos, notes, trash, stats }
  },

  loadDueReminders: () => request<TodoReminder[]>('/reminders/due'),

  createPlan: (item: Plan) => request<ApiPlan>('/plans', { method: 'POST', body: JSON.stringify({ title: item.title, description: item.subtitle, color: item.color, status: item.status, dueDate: item.dueDate || null }) }),
  updatePlan: (item: Plan) => request<ApiPlan>(`/plans/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, description: item.subtitle, color: item.color, status: item.status, dueDate: item.dueDate || null, expectedVersion: item.version }) }),
  deletePlan: (id: string) => request<void>(`/plans/${id}`, { method: 'DELETE' }),

  createSchedule: (item: CalendarItem) => request<ApiSchedule>('/schedules', { method: 'POST', body: JSON.stringify({ title: item.title, startAt: `${item.date}T${item.time}:00`, durationMinutes: item.duration, status: item.status, planId: item.planId ?? null, stageId: item.stageId ?? null, taskId: item.taskId ?? null, locationName: item.locationName ?? null, latitude: item.latitude ?? null, longitude: item.longitude ?? null, coordinateSystem: item.coordinateSystem ?? null, timezoneId: item.timezoneId ?? null, sourceUrl: item.sourceUrl ?? null, reservationRequired: item.reservationRequired ?? null }) }),
  updateSchedule: (item: CalendarItem) => request<ApiSchedule>(`/schedules/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, startAt: `${item.date}T${item.time}:00`, durationMinutes: item.duration, status: item.status, planId: item.planId ?? null, stageId: item.stageId ?? null, taskId: item.taskId ?? null, locationName: item.locationName ?? null, latitude: item.latitude ?? null, longitude: item.longitude ?? null, coordinateSystem: item.coordinateSystem ?? null, timezoneId: item.timezoneId ?? null, sourceUrl: item.sourceUrl ?? null, reservationRequired: item.reservationRequired ?? null, expectedVersion: item.version }) }),
  deleteSchedule: (id: string) => request<void>(`/schedules/${id}`, { method: 'DELETE' }),
  deleteSchedules: (ids: string[]) => request<{ deleted: number }>('/schedules/batch', { method: 'DELETE', body: JSON.stringify({ ids }) }),
  loadScheduleMaterials: (id: string, refresh = false) => request<ScheduleMaterialsResponse>(`/schedules/${id}/materials${refresh ? '?refresh=true' : ''}`),

  createTodo: (item: TodoItem) => request<ApiTodo>('/todos', { method: 'POST', body: JSON.stringify({ title: item.title, dueAt: todoDueAt(item), status: item.done ? 'done' : 'pending', priority: priorityToApi(item.priority), reminderMinutes: reminderToMinutes(item.reminder) }) }),
  updateTodo: (item: TodoItem) => request<ApiTodo>(`/todos/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, dueAt: todoDueAt(item), status: item.done ? 'done' : 'pending', priority: priorityToApi(item.priority), reminderMinutes: reminderToMinutes(item.reminder), expectedVersion: item.version }) }),
  deleteTodo: (id: string) => request<void>(`/todos/${id}`, { method: 'DELETE' }),

  createPlanStage: (planId: string, item: PlanItem) => request<ApiStage>(`/plans/${planId}/stages`, { method: 'POST', body: JSON.stringify({ title: item.title, dueLabel: item.dueLabel }) }),
  updatePlanStage: (planId: string, item: PlanItem) => request<ApiStage>(`/plans/${planId}/stages/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, dueLabel: item.dueLabel, expectedVersion: item.version }) }),
  deletePlanStage: (planId: string, id: string, expectedVersion: number) => request<void>(`/plans/${planId}/stages/${id}`, { method: 'DELETE', body: JSON.stringify({ expectedVersion }) }),

  createTask: (item: Omit<PlanTask, 'id' | 'version' | 'status' | 'scheduleCount' | 'completedScheduleCount' | 'scheduleProgress'>) => request<ApiTask>('/tasks', { method: 'POST', body: JSON.stringify(item) }),
  updateTask: (item: PlanTask) => request<ApiTask>(`/tasks/${item.id}`, { method: 'PUT', body: JSON.stringify({
    title: item.title,
    description: item.description,
    priority: item.priority,
    estimatedMinutes: item.estimatedMinutes,
    actualMinutes: item.actualMinutes,
    dueAt: item.dueAt,
    recurrenceType: item.recurrenceType,
    scheduleStartDate: item.scheduleStartDate,
    recurrenceEndDate: item.recurrenceEndDate,
    scheduledTime: item.scheduledTime,
    reason: item.reason,
    expectedVersion: item.version,
  }) }),
  taskAction: (item: PlanTask, action: 'complete' | 'delay' | 'block' | 'skip' | 'cancel' | 'reopen', fields: Record<string, unknown> = {}) => request<ApiTask>(`/tasks/${item.id}/${action}`, { method: 'POST', body: JSON.stringify({ ...fields, expectedVersion: item.version }) }),
  deleteTask: (item: PlanTask) => request<void>(`/tasks/${item.id}`, { method: 'DELETE', body: JSON.stringify({ expectedVersion: item.version }) }),

  loadTrash: () => request<TrashItem[]>('/trash'),
  restoreTrashItem: (item: TrashItem) => request<unknown>(`/trash/${item.type}/${item.id}/restore`, { method: 'POST' }),
  purgeTrashItem: (item: TrashItem) => request<void>(`/trash/${item.type}/${item.id}`, { method: 'DELETE' }),

  createNote: (item: Note) => request<ApiNote>('/notes', { method: 'POST', body: JSON.stringify({ title: item.title, category: item.category, excerpt: item.excerpt, content: item.content ?? item.excerpt, sourceType: 'manual' }) }),
  updateNote: (item: Note) => request<ApiNote>(`/notes/${item.id}`, { method: 'PUT', body: JSON.stringify({ title: item.title, category: item.category, excerpt: item.excerpt, content: item.content ?? item.excerpt }) }),
  deleteNote: (id: string) => request<void>(`/notes/${id}`, { method: 'DELETE' }),
  createNoteRelation: (fromNoteId: string, toNoteId: string) => request<void>(`/notes/${fromNoteId}/relations`, { method: 'POST', body: JSON.stringify({ toNoteId }) }),
  deleteNoteRelation: (fromNoteId: string, toNoteId: string) => request<void>(`/notes/${fromNoteId}/relations`, { method: 'DELETE', body: JSON.stringify({ toNoteId }) }),
  generateNoteContent: (context: NoteGenerateContext) => request<NoteGenerateResponse>('/notes/generate', { method: 'POST', body: JSON.stringify(context) }),
  uploadNoteImage: async (file: File) => {
    const response = await fetch(API_BASE + '/notes/images', {
      method: 'POST',
      headers: { 'Content-Type': file.type || 'image/png' },
      body: file,
    })
    if (!response.ok) {
      let payload: { message?: string; error?: string } | undefined
      try { payload = await response.json() as { message?: string; error?: string } }
      catch { payload = undefined }
      throw new Error(payload?.message || payload?.error || `图片上传失败（${response.status}）`)
    }
    return response.json() as Promise<{ url: string; filename: string; size: number }>
  },
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
  sendAiCommand: (message: string, conversationId?: string) => request<AiCommandResponse>('/ai/commands', {
    method: 'POST',
    body: JSON.stringify({ message, conversationId }),
  }),
  startAgent: (message: string, conversationId?: string, documentIds: string[] = [], agentArguments?: Record<string, unknown>) => request<AgentRunResponse>('/agent/runs', {
    method: 'POST',
    body: JSON.stringify({ message, conversationId, documentIds, arguments: agentArguments }),
  }),
  resumeAgent: (runId: string, message: string, documentIds: string[] = [], agentArguments?: Record<string, unknown>) => request<AgentRunResponse>(`/agent/runs/${runId}/resume`, {
    method: 'POST',
    body: JSON.stringify({ message, documentIds, arguments: agentArguments }),
  }),
  uploadAgentFile: async (file: File) => {
    const response = await fetch(API_BASE + '/agent/files', {
      method: 'POST',
      headers: {
        'Content-Type': file.type || 'application/octet-stream',
        'X-File-Name': encodeURIComponent(file.name),
      },
      body: file,
    })
    if (!response.ok) {
      let payload: { message?: string; error?: string } | undefined
      try { payload = await response.json() as { message?: string; error?: string } }
      catch { payload = undefined }
      throw new Error(payload?.message || payload?.error || `文件上传失败（${response.status}）`)
    }
    return response.json() as Promise<AgentDocument>
  },
  loadAgentRun: (runId: string) => request<AgentRunResponse>(`/agent/runs/${runId}`),
  confirmAgentDraft: (id: string) => request<{ id: string; changeSetId: string; status: string; runId?: string; runStatus?: string; executed: AiDraftAction[] }>(`/agent/drafts/${id}/confirm`, { method: 'POST' }),
  cancelAgentDraft: (id: string) => request<{ id: string; status: string; runId?: string; runStatus?: string }>(`/agent/drafts/${id}/cancel`, { method: 'POST' }),
  confirmAiDraft: (id: string) => request<{ id: string; changeSetId: string; status: string; executed: AiDraftAction[] }>(`/ai/drafts/${id}/confirm`, { method: 'POST' }),
  cancelAiDraft: (id: string) => request<{ id: string; status: string }>(`/ai/drafts/${id}/cancel`, { method: 'POST' }),
  loadAiSession: () => request<AiSession>('/ai/session'),
  loadAiConversations: () => request<AiConversation[]>('/ai/conversations'),
  createAiConversation: () => request<AiConversationDetail>('/ai/conversations', { method: 'POST' }),
  loadAiConversation: (id: string) => request<AiConversationDetail>(`/ai/conversations/${id}`),
  renameAiConversation: (id: string, title: string) => request<{ id: string; title: string }>(`/ai/conversations/${id}`, {
    method: 'PATCH', body: JSON.stringify({ title }),
  }),
  deleteAiConversation: (id: string) => request<void>(`/ai/conversations/${id}`, { method: 'DELETE' }),
  loadAiMemories: () => request<AiMemory[]>('/ai/memories'),
  updateAiMemory: (id: string, content: string) => request<AiMemory>(`/ai/memories/${id}`, {
    method: 'PATCH', body: JSON.stringify({ content }),
  }),
  deleteAiMemory: (id: string) => request<void>(`/ai/memories/${id}`, { method: 'DELETE' }),
  undoChangeSet: (id: string) => request<{ status: string; restored: number }>(`/ai/change-sets/${id}/undo`, { method: 'POST' }),
  loadPreference: () => request<PlanningPreference>('/planning/preferences'),
  savePreference: (value: PlanningPreference) => request<PlanningPreference>('/planning/preferences', { method: 'PUT', body: JSON.stringify(value) }),
  loadProfile: () => request<UserProfile>('/profile'),
  saveProfile: (value: UserProfile) => request<UserProfile>('/profile', { method: 'PUT', body: JSON.stringify(value) }),
  reviewFacts: () => request<ReviewFacts>('/review/facts'),
   loadTodayReview: async () => normalizeReviewReport(await request<ReviewReport>('/review/today')),
   regenerateTodayReview: async () => normalizeReviewReport(await request<ReviewReport>('/review/today/regenerate', { method: 'POST' })),
  chatReview: (message: string, history: AiReviewMessage[], conversationId?: string) => request<AiReviewResponse>('/ai/review/chat', {
    method: 'POST',
    body: JSON.stringify({ message, history, conversationId }),
  }),
}
