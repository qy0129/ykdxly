import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Brain, Check, CheckCircle2, ChevronDown, ChevronRight, Clock3, FileText, LoaderCircle, MessageSquare, Paperclip, Pencil, Plus, RefreshCw, Save, Send, Trash2, Workflow, X, XCircle } from 'lucide-react'
import {
  plannerApi,
  type AgentDocument,
  type AgentInputRequirement,
  type AgentRunResponse,
  type AgentRunStep,
  type AiConversation,
  type AiDraft,
  type AiMemory,
  type AiSession,
} from '../../services/plannerApi'

type ChatMessage = { role: 'user' | 'assistant'; content: string; imageUrls?: string[] }

type TravelInfoForm = {
  destination: string
  origin: string
  startDate: string
  endDate: string
  travelers: string
  budgetAmount: string
  budgetCurrency: string
  pace: string
  interests: string
  remarks: string
  title: string
  domain: string
  currentLevel: string
  targetLevel: string
  targetDate: string
  weeklyHours: string
  availableDays: string
  learningStyle: string
  goalId: string
}

const emptyTravelInfoForm: TravelInfoForm = {
  destination: '', origin: '', startDate: '', endDate: '', travelers: '1',
  budgetAmount: '', budgetCurrency: 'CNY', pace: 'relaxed', interests: '', remarks: '', title: '', domain: '',
  currentLevel: '', targetLevel: '', targetDate: '', weeklyHours: '', availableDays: '', learningStyle: '', goalId: '',
}

const welcomeMessages = (): ChatMessage[] => [{ role: 'assistant', content: '今天想推进什么？' }]

const memoryCategory: Record<AiMemory['category'], string> = {
  preference: '偏好',
  personality: '个性',
  communication_style: '沟通风格',
  long_term_goal: '长期目标',
  constraint: '长期限制',
  personal_fact: '个人信息',
}

function conversationTime(value: string) {
  const date = new Date(value)
  const today = new Date()
  if (date.toDateString() === today.toDateString()) return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

const stepStatusLabel: Record<string, string> = {
  RUNNING: '执行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  WAITING_CONFIRMATION: '待确认',
  WAITING_USER: '待补充',
}

function stepStatusIcon(status: string) {
  switch (status) {
    case 'COMPLETED': return <CheckCircle2 size={12} />
    case 'FAILED':
    case 'CANCELLED': return <XCircle size={12} />
    case 'RUNNING': return <LoaderCircle className="spin" size={12} />
    default: return <Clock3 size={12} />
  }
}

function fmtDuration(ms?: number) {
  if (ms == null) return ''
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

function imageUrlsFromRun(run?: AgentRunResponse) {
  if (!run) return []
  const urls = [run.imageUrl, ...(run.images ?? []).map((item) => item.imageUrl)]
  return urls.filter((url): url is string => Boolean(url && isImageUrl(url)))
}

function imageUrlsFromMessages(messages: AiSession['messages']) {
  return messages.flatMap((message) => message.imageUrls ?? [])
    .filter((url): url is string => isImageUrl(url))
}

function isImageUrl(url: string) {
  return /^https?:\/\//i.test(url) || url.startsWith('/api/ai/images/') || url.startsWith('/uploads/ai/')
}

const draftActionLabels: Record<string, string> = {
  create_plan: '创建计划',
  create_stage: '创建阶段',
  create_task: '创建任务',
  create_todo: '创建待办',
  create_schedule: '加入日程',
  update_plan: '调整计划',
  update_task: '调整任务',
  update_todo: '调整待办',
  update_schedule: '调整日程',
  delete_plan: '删除计划',
  delete_task: '删除任务',
  delete_todo: '删除待办',
  delete_schedule: '删除日程',
}

const draftFieldLabels: Record<string, string> = {
  title: '标题',
  description: '说明',
  dueDate: '截止日期',
  dueAt: '截止时间',
  startAt: '开始时间',
  durationMinutes: '时长',
  priority: '优先级',
  status: '状态',
}

function formatDraftValue(value: unknown) {
  if (value == null || value === '') return '未设置'
  if (typeof value === 'object') return '已包含详细内容'
  return String(value)
}

function draftActionDetails(action: AiDraft['actions'][number]) {
  const fields = action.fields ?? {}
  if (action.type === 'create_plan') {
    const stages = Array.isArray(fields.stages) ? fields.stages : []
    const taskCount = stages.reduce((total, stage) => {
      if (!stage || typeof stage !== 'object') return total
      const tasks = (stage as { tasks?: unknown }).tasks
      return total + (Array.isArray(tasks) ? tasks.length : 0)
    }, 0)
    return [
      fields.title ? `计划：${formatDraftValue(fields.title)}` : '',
      stages.length ? `阶段：${stages.length} 个` : '',
      taskCount ? `任务：${taskCount} 个` : '',
    ].filter(Boolean)
  }
  return Object.entries(fields)
    .filter(([key]) => key in draftFieldLabels)
    .slice(0, 3)
    .map(([key, value]) => `${draftFieldLabels[key]}：${formatDraftValue(value)}`)
}

function draftPlanPreview(action: AiDraft['actions'][number]) {
  if (action.type !== 'create_plan') return null
  const fields = action.fields ?? {}
  const stages = Array.isArray(fields.stages) ? fields.stages : []
  if (!stages.length) return null
  return (
    <div className="ai-draft-plan-preview">
      {stages.map((stage, index) => {
        if (!stage || typeof stage !== 'object') return null
        const item = stage as { title?: unknown; dueDate?: unknown; tasks?: unknown[] }
        const tasks = Array.isArray(item.tasks) ? item.tasks : []
        return (
          <div className="ai-draft-stage" key={`${String(item.dueDate ?? '')}-${index}`}>
            <strong>第 {index + 1} 天 · {String(item.title ?? '旅行日程')}</strong>
            {item.dueDate != null && <small>{String(item.dueDate)}</small>}
            {tasks.map((task, taskIndex) => {
              if (!task || typeof task !== 'object') return null
              const value = task as { title?: unknown; description?: unknown; dueAt?: unknown; schedules?: unknown[] }
              const schedules = Array.isArray(value.schedules) ? value.schedules : []
              return (
                <div className="ai-draft-task" key={`${String(value.title ?? '')}-${taskIndex}`}>
                  <span>{String(value.title ?? '行程任务')}</span>
                  {value.description != null && <p>{String(value.description)}</p>}
                  {schedules.map((schedule, scheduleIndex) => {
                    if (!schedule || typeof schedule !== 'object') return null
                    const item = schedule as { startAt?: unknown; durationMinutes?: unknown }
                    return <small key={scheduleIndex}>日程：{String(item.startAt ?? value.dueAt ?? '未设置')}{item.durationMinutes ? ` · ${String(item.durationMinutes)} 分钟` : ''}</small>
                  })}
                </div>
              )
            })}
          </div>
        )
      })}
    </div>
  )
}

function travelPlanPreview(data: Record<string, unknown>) {
  const request = data.request && typeof data.request === 'object' ? data.request as Record<string, unknown> : {}
  const days = Array.isArray(data.days) ? data.days : []
  return (
    <div className="ai-travel-plan-preview">
      <div className="ai-travel-plan-meta">
        <strong>{String(request.destination ?? '旅行目的地')}旅行计划</strong>
        <span>{String(request.startDate ?? '')}{request.endDate ? ` 至 ${String(request.endDate)}` : ''}</span>
      </div>
      {days.map((day, index) => {
        if (!day || typeof day !== 'object') return null
        const item = day as { title?: unknown; date?: unknown; activities?: unknown[] }
        const activities = Array.isArray(item.activities) ? item.activities : []
        return (
          <div className="ai-travel-day" key={`${String(item.date ?? '')}-${index}`}>
            <strong>第 {index + 1} 天 · {String(item.title ?? '旅行日程')}</strong>
            <small>{String(item.date ?? '')}</small>
            <div className="ai-travel-activities">
              {activities.map((activity, activityIndex) => {
                if (!activity || typeof activity !== 'object') return null
                const value = activity as { startTime?: unknown; title?: unknown; location?: unknown; notes?: unknown }
                return <p key={activityIndex}><b>{String(value.startTime || '安排')}</b>{String(value.title ?? '活动')}<em>{value.location ? ` · ${String(value.location)}` : ''}</em></p>
              })}
            </div>
          </div>
        )
      })}
    </div>
  )
}

export function AgentPage({ seed, onDataChanged }: { seed?: string; onDataChanged: () => void }) {
  const [messages, setMessages] = useState<ChatMessage[]>(welcomeMessages)
  const [conversations, setConversations] = useState<AiConversation[]>([])
  const [selectedConversationIds, setSelectedConversationIds] = useState<string[]>([])
  const [draft, setDraft] = useState<AiDraft>()
  const [planReview, setPlanReview] = useState<Record<string, unknown>>()
  const [inputRequirements, setInputRequirements] = useState<AgentInputRequirement[]>([])
  const [informationFormOpen, setInformationFormOpen] = useState(false)
  const [informationForm, setInformationForm] = useState<TravelInfoForm>(emptyTravelInfoForm)
  const [input, setInput] = useState('')
  const [conversationId, setConversationId] = useState<string>()
  const [runId, setRunId] = useState<string>()
  const [runStatus, setRunStatus] = useState<AgentRunResponse['status']>()
  const [runSteps, setRunSteps] = useState<AgentRunStep[]>([])
  const [stepsOpen, setStepsOpen] = useState(false)
  const [waitSeconds, setWaitSeconds] = useState(0)
  const [pollFailureCount, setPollFailureCount] = useState(0)
  const [busy, setBusy] = useState(false)
  const [loadingConversation, setLoadingConversation] = useState(false)
  const [error, setError] = useState('')
  const [lastChangeSet, setLastChangeSet] = useState<string>()
  const [attachments, setAttachments] = useState<AgentDocument[]>([])
  const [uploading, setUploading] = useState(false)
  const [renamingId, setRenamingId] = useState<string>()
  const [renamingTitle, setRenamingTitle] = useState('')
  const [memoryOpen, setMemoryOpen] = useState(false)
  const [memories, setMemories] = useState<AiMemory[]>([])
  const [editingMemoryId, setEditingMemoryId] = useState<string>()
  const [editingMemoryText, setEditingMemoryText] = useState('')
  const fileInput = useRef<HTMLInputElement>(null)
  const messageList = useRef<HTMLDivElement>(null)
  const activeConversationIdRef = useRef<string | undefined>(undefined)

  const showInformationForm = (requirements: AgentInputRequirement[] | undefined, data?: Record<string, unknown>) => {
    if (!requirements?.length) {
      setInputRequirements([])
      setInformationFormOpen(false)
      return
    }
    const unchanged = informationFormOpen
      && inputRequirements.length === requirements.length
      && inputRequirements.every((item, index) => item.field === requirements[index].field)
    if (unchanged) return
    const request = data?.request && typeof data.request === 'object' ? data.request as Record<string, unknown> : {}
    const budget = request.budget && typeof request.budget === 'object' ? request.budget as Record<string, unknown> : {}
    setInformationForm((current) => ({
      ...current,
      destination: String(request.destination ?? current.destination),
      origin: String(request.origin ?? current.origin),
      startDate: String(request.startDate ?? current.startDate),
      endDate: String(request.endDate ?? current.endDate),
      travelers: String(request.travelers ?? current.travelers),
      budgetAmount: String(budget.amount ?? current.budgetAmount),
      budgetCurrency: String(budget.currency ?? current.budgetCurrency),
      pace: String(request.pace ?? current.pace),
      interests: Array.isArray(request.interests) ? request.interests.join(', ') : current.interests,
      title: String(request.title ?? current.title),
      domain: String(request.domain ?? current.domain),
      currentLevel: String(request.currentLevel ?? current.currentLevel),
      targetLevel: String(request.targetLevel ?? current.targetLevel),
      targetDate: String(request.targetDate ?? current.targetDate),
      weeklyHours: String(request.weeklyHours ?? current.weeklyHours),
      availableDays: Array.isArray(request.availableDays) ? request.availableDays.join(', ') : String(request.availableDays ?? current.availableDays),
      learningStyle: String(request.learningStyle ?? current.learningStyle),
      goalId: String(request.goalId ?? current.goalId),
    }))
    setInputRequirements(requirements)
    setInformationFormOpen(true)
  }

  const applySession = (session: AiSession) => {
    activeConversationIdRef.current = session.conversationId
    setConversationId(session.conversationId)
    setRunId(session.runId)
    setRunStatus(session.runStatus)
    setRunSteps([])
    setStepsOpen(false)
    setWaitSeconds(0)
    setPollFailureCount(0)
    setMessages(session.messages.length
      ? session.messages.map(({ role, content, imageUrls }) => ({ role, content, imageUrls }))
      : welcomeMessages())
    setDraft(session.draft)
    setPlanReview(session.planReview && session.travelData ? session.travelData : undefined)
    showInformationForm(session.inputRequirements, session.travelData)
    setLastChangeSet(undefined)
    setAttachments([])
  }

  const refreshConversations = async () => {
    const value = await plannerApi.loadAiConversations()
    setConversations(value)
    const ids = new Set(value.filter((conversation) => conversation.runStatus !== 'RUNNING').map((conversation) => conversation.id))
    setSelectedConversationIds((current) => current.filter((id) => ids.has(id)))
    return value
  }

  useEffect(() => {
    let active = true
    void Promise.all([plannerApi.loadAiSession(), plannerApi.loadAiConversations()]).then(async ([session, list]) => {
      if (!active) return
      if (session.conversationId) {
        setConversations(list)
        applySession(session)
        return
      }
      const created = await plannerApi.createAiConversation()
      if (!active) return
      applySession(created)
      await refreshConversations()
    }).catch((cause) => setError(cause instanceof Error ? cause.message : '无法恢复 AI 会话'))
    return () => { active = false }
  }, [])

  useEffect(() => { if (seed) setInput(seed) }, [seed])

  useEffect(() => {
    messageList.current?.scrollTo({ top: messageList.current.scrollHeight, behavior: 'smooth' })
  }, [messages, busy, draft])

  const runningKey = conversations
    .filter((conversation) => conversation.runStatus === 'RUNNING' && conversation.runId)
    .map((conversation) => `${conversation.id}:${conversation.runId}`)
    .sort()
    .join('|')

  useEffect(() => {
    const running = runningKey ? runningKey.split('|').map((value) => {
      const [id, activeRunId] = value.split(':')
      return { id, runId: activeRunId }
    }) : []
    if (!running.length) return
    let stopped = false
    let timer: number | undefined
    const poll = async () => {
      try {
        const states = await Promise.all(running.map(async (conversation) => ({
          conversation,
          state: await plannerApi.loadAgentRun(conversation.runId),
        })))
        setPollFailureCount(0)
        const currentState = states.find(({ conversation }) => conversation.id === activeConversationIdRef.current)?.state
        if (currentState?.steps?.length) setRunSteps(currentState.steps)
          const currentTravelData = currentState?.travelData ?? currentState?.data
          if (currentState?.planReview && currentTravelData) setPlanReview(currentTravelData)
          showInformationForm(currentState?.inputRequirements, currentTravelData)
        if (currentState?.status === 'RUNNING') setWaitSeconds((seconds) => seconds + 1)
        const completed = states.filter(({ state }) => state.status !== 'RUNNING')
        if (completed.length) {
          const currentId = activeConversationIdRef.current
          const currentResult = completed.find(({ conversation }) => conversation.id === currentId)
          const [list, detail] = await Promise.all([
            plannerApi.loadAiConversations(),
            currentResult && currentId ? plannerApi.loadAiConversation(currentId) : Promise.resolve(undefined),
          ])
          if (stopped) return
          setConversations(list)
          const ids = new Set(list.map((conversation) => conversation.id))
          setSelectedConversationIds((current) => current.filter((id) => ids.has(id)))
          if (detail && activeConversationIdRef.current === currentId) {
            applySession(detail)
            if (currentResult?.state.steps?.length) setRunSteps(currentResult.state.steps)
              const completedTravelData = currentResult?.state.travelData ?? currentResult?.state.data
              if (currentResult?.state.planReview && completedTravelData) setPlanReview(completedTravelData)
              showInformationForm(currentResult?.state.inputRequirements, completedTravelData)
            if (currentResult?.state.status === 'FAILED') setError(currentResult.state.lastError || 'Agent 执行失败')
            const generatedImages = imageUrlsFromRun(currentResult?.state)
            const restoredImages = new Set(imageUrlsFromMessages(detail.messages))
            const missingImages = generatedImages.filter((url) => !restoredImages.has(url))
            if (missingImages.length) {
              setMessages((current) => [...current, { role: 'assistant', content: '', imageUrls: missingImages }])
            }
          }
        }
      } catch {
        // 短暂轮询失败时保持运行状态，下次继续查询；连续多次失败则给用户可见提示。
        setPollFailureCount((count) => count + 1)
      }
      if (!stopped) timer = window.setTimeout(() => void poll(), 1000)
    }
    timer = window.setTimeout(() => void poll(), 500)
    return () => { stopped = true; if (timer) window.clearTimeout(timer) }
  }, [runningKey])

  const openConversation = async (id: string) => {
    if (id === conversationId || loadingConversation) return
    setLoadingConversation(true)
    setError('')
    try {
      const detail = await plannerApi.loadAiConversation(id)
      applySession(detail)
      setInput('')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法打开对话')
    } finally { setLoadingConversation(false) }
  }

  const createConversation = async () => {
    if (busy || loadingConversation) return
    setLoadingConversation(true)
    setError('')
    try {
      const detail = await plannerApi.createAiConversation()
      applySession(detail)
      setInput('')
      await refreshConversations()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法新建对话')
    } finally { setLoadingConversation(false) }
  }

  const saveConversationTitle = async (id: string) => {
    const title = renamingTitle.trim()
    if (!title) return
    try {
      await plannerApi.renameAiConversation(id, title)
      setConversations((current) => current.map((item) => item.id === id ? { ...item, title } : item))
      setRenamingId(undefined)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法重命名对话')
    }
  }

  const deleteConversation = async (id: string) => {
    if (busy || conversations.some((conversation) => conversation.id === id && conversation.runStatus === 'RUNNING')) return
    if (!window.confirm('删除后无法恢复这个对话，确定继续吗？')) return
    setError('')
    try {
      await plannerApi.deleteAiConversation(id)
      const remaining = await refreshConversations()
      if (id !== conversationId) return
      if (remaining.length) {
        const detail = await plannerApi.loadAiConversation(remaining[0].id)
        applySession(detail)
      } else {
        const created = await plannerApi.createAiConversation()
        applySession(created)
        await refreshConversations()
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法删除对话')
    }
  }

  const toggleConversationSelection = (id: string) => {
    setSelectedConversationIds((current) => current.includes(id)
      ? current.filter((value) => value !== id)
      : [...current, id])
  }

  const deleteSelectedConversations = async () => {
    const deletingIds = selectedConversationIds.filter((id) => conversations.some((conversation) => conversation.id === id && conversation.runStatus !== 'RUNNING'))
    if (busy || loadingConversation || deletingIds.length === 0
      || !window.confirm(`确定删除选中的 ${deletingIds.length} 个对话吗？删除后无法恢复。`)) return
    setLoadingConversation(true)
    setError('')
    try {
      await Promise.all(deletingIds.map((id) => plannerApi.deleteAiConversation(id)))
      const remaining = await refreshConversations()
      setSelectedConversationIds([])
      if (!conversationId || !deletingIds.includes(conversationId)) return
      if (remaining.length) {
        applySession(await plannerApi.loadAiConversation(remaining[0].id))
      } else {
        const created = await plannerApi.createAiConversation()
        applySession(created)
        await refreshConversations()
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法批量删除对话')
    } finally {
      setLoadingConversation(false)
    }
  }

  const toggleMemories = async () => {
    const next = !memoryOpen
    setMemoryOpen(next)
    if (!next) return
    setError('')
    try { setMemories(await plannerApi.loadAiMemories()) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '无法读取长期记忆') }
  }

  const saveMemory = async (id: string) => {
    const content = editingMemoryText.trim()
    if (!content) return
    try {
      const updated = await plannerApi.updateAiMemory(id, content)
      setMemories((current) => current.map((item) => item.id === id ? updated : item))
      setEditingMemoryId(undefined)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '无法保存记忆') }
  }

  const deleteMemory = async (id: string) => {
    if (!window.confirm('确定删除这条长期记忆吗？')) return
    try {
      await plannerApi.deleteAiMemory(id)
      setMemories((current) => current.filter((item) => item.id !== id))
    } catch (cause) { setError(cause instanceof Error ? cause.message : '无法删除记忆') }
  }

  const selectFiles = async (files: FileList | null) => {
    const selected = Array.from(files ?? [])
    if (!selected.length || uploading) return
    setUploading(true)
    setError('')
    try {
      for (const file of selected) {
        if (file.size > 25 * 1024 * 1024) throw new Error(`${file.name} 超过 25 MB`)
        const document = await plannerApi.uploadAgentFile(file)
        setAttachments((current) => current.some((item) => item.id === document.id)
          ? current : [...current, document])
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '文件上传失败')
    } finally {
      setUploading(false)
      if (fileInput.current) fileInput.current.value = ''
    }
  }

  const send = async (messageOverride?: string, argumentsOverride?: Record<string, unknown>) => {
    const message = messageOverride?.trim() || input.trim() || (attachments.length ? '请分析这些文件' : '')
    const currentRunning = conversations.some((conversation) => conversation.id === conversationId && conversation.runStatus === 'RUNNING')
      || runStatus === 'RUNNING'
    if (!message || busy || uploading || loadingConversation || draft || currentRunning) return
    const documentIds = attachments.map((item) => item.id)
    const displayMessage = attachments.length
      ? `${message}\n\n附件：${attachments.map((item) => item.fileName).join('、')}` : message
    setMessages((current) => [...current, { role: 'user', content: displayMessage }])
    setInput('')
      setBusy(true)
    setError('')
    try {
      let activeConversationId = conversationId
      if (!activeConversationId) {
        const created = await plannerApi.createAiConversation()
        activeConversationId = created.conversationId
        activeConversationIdRef.current = activeConversationId
        setConversationId(activeConversationId)
        await refreshConversations()
      }
      const targetRunId = runId
      const targetRunStatus = runStatus
      const response = targetRunId && (targetRunStatus === 'WAITING_USER' || targetRunStatus === 'FAILED')
        ? await plannerApi.resumeAgent(targetRunId, message, documentIds, argumentsOverride)
        : await plannerApi.startAgent(message, activeConversationId, documentIds, argumentsOverride)
      setConversations((current) => current.map((conversation) => conversation.id === activeConversationId
        ? { ...conversation, runId: response.runId, runStatus: 'RUNNING' }
        : conversation))
      setSelectedConversationIds((current) => current.filter((id) => id !== activeConversationId))
      if (activeConversationIdRef.current === activeConversationId) {
        setRunId(response.runId)
      setRunStatus('RUNNING')
        setWaitSeconds(0)
        setPollFailureCount(0)
        setAttachments([])
      }
      await refreshConversations()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法提交 Agent 请求')
    } finally { setBusy(false) }
  }

  const approveTravelPlan = async () => {
    await send('确认行程，生成写入计划和日历草案')
  }

  const submitInformationForm = async (event: FormEvent) => {
    event.preventDefault()
    const required = inputRequirements.filter((item) => item.required !== false)
    const values: Record<string, string> = Object.fromEntries(Object.keys(informationForm).map((key) => [key, informationForm[key as keyof TravelInfoForm]]))
    const missing = required.find((item) => item.field in values && !values[item.field].trim())
    if (missing) { setError(`请填写${missing.label}`); return }
    const argumentsValue: Record<string, unknown> = {
      destination: informationForm.destination.trim(),
      origin: informationForm.origin.trim(),
      startDate: informationForm.startDate,
      endDate: informationForm.endDate,
      travelers: Number(informationForm.travelers || 1),
      pace: informationForm.pace,
      interests: informationForm.interests.split(/[,，]/).map((item) => item.trim()).filter(Boolean),
      remarks: informationForm.remarks.trim(),
      title: informationForm.title.trim(), domain: informationForm.domain.trim(), currentLevel: informationForm.currentLevel.trim(),
      targetLevel: informationForm.targetLevel.trim(), targetDate: informationForm.targetDate, weeklyHours: Number(informationForm.weeklyHours || 0),
      availableDays: informationForm.availableDays.split(/[,，]/).map((item) => item.trim()).filter(Boolean), learningStyle: informationForm.learningStyle.trim(),
      goalId: informationForm.goalId.trim(),
    }
    if (informationForm.budgetAmount.trim()) {
      argumentsValue.budget = { amount: Number(informationForm.budgetAmount), currency: informationForm.budgetCurrency || 'CNY' }
    }
    const remarks = informationForm.remarks.trim()
    const learningForm = inputRequirements.some((item) => ['title', 'domain', 'weeklyHours', 'targetDate'].includes(item.field))
    await send(remarks
      ? `请结合信息搜集表生成${learningForm ? '学习计划' : '旅行计划'}。备注：${remarks}`
      : `请结合信息搜集表生成${learningForm ? '学习计划' : '旅行计划'}。`, argumentsValue)
    setInformationFormOpen(false)
  }

  const informationFieldRequired = (field: string) => inputRequirements.some((item) => item.field === field && item.required !== false)
  const learningInformationForm = inputRequirements.some((item) => !['destination', 'origin', 'startDate', 'endDate', 'travelers', 'budget', 'pace', 'interests'].includes(item.field))

  const confirm = async () => {
    if (!draft || busy) return
    setBusy(true)
    setError('')
    try {
      const result = await plannerApi.confirmAgentDraft(draft.id)
      setMessages((current) => [...current, { role: 'assistant', content: `已执行 ${result.executed.length} 项操作。` }])
      setRunStatus('COMPLETED')
      setLastChangeSet(result.changeSetId)
      setDraft(undefined)
      setPlanReview(undefined)
      await refreshConversations()
      onDataChanged()
    } catch (cause) {
      setError(cause instanceof Error ? '草案无法确认：' + cause.message : '草案无法确认。')
    } finally { setBusy(false) }
  }

  const cancel = async () => {
    if (!draft || busy) return
    setBusy(true)
    setError('')
    try {
      await plannerApi.cancelAgentDraft(draft.id)
      setMessages((current) => [...current, { role: 'assistant', content: '草案已取消，计划数据没有变化。' }])
      setRunStatus('CANCELLED')
      setDraft(undefined)
      setPlanReview(undefined)
      await refreshConversations()
    } catch (cause) {
      setError(cause instanceof Error ? '草案无法取消：' + cause.message : '草案无法取消。')
    } finally { setBusy(false) }
  }

  const undo = async () => {
    if (!lastChangeSet || busy) return
    setBusy(true)
    setError('')
    try {
      const result = await plannerApi.undoChangeSet(lastChangeSet)
      setMessages((current) => [...current, { role: 'assistant', content: `已撤销本次变更，共恢复 ${result.restored} 项。` }])
      setLastChangeSet(undefined)
      onDataChanged()
    } catch (cause) { setError(cause instanceof Error ? cause.message : '无法撤销变更') }
    finally { setBusy(false) }
  }

  const selectableConversations = conversations.filter((conversation) => conversation.runStatus !== 'RUNNING')
  const allConversationsSelected = selectableConversations.length > 0
    && selectableConversations.every((conversation) => selectedConversationIds.includes(conversation.id))
  const currentConversationRunning = runStatus === 'RUNNING'
    || conversations.some((conversation) => conversation.id === conversationId && conversation.runStatus === 'RUNNING')

  return (
    <div className="agent-page content-page">
      <div className={`ai-shell${memoryOpen ? ' memory-open' : ''}`}>
        <aside className="ai-conversation-sidebar">
          <div className="ai-sidebar-heading">
            <strong>对话</strong>
            <div className="ai-sidebar-actions">
              {selectableConversations.length > 0 && <input className="batch-checkbox" type="checkbox" checked={allConversationsSelected} disabled={busy || loadingConversation} onChange={() => setSelectedConversationIds(allConversationsSelected ? [] : selectableConversations.map((conversation) => conversation.id))} title="全选空闲对话" aria-label="全选空闲对话" />}
              {selectedConversationIds.length > 0 && <button className="icon-button danger-text ai-batch-delete" type="button" title={`删除选中的 ${selectedConversationIds.length} 个对话`} aria-label={`删除选中的 ${selectedConversationIds.length} 个对话`} disabled={busy || loadingConversation} onClick={() => void deleteSelectedConversations()}><Trash2 size={16} /><small>{selectedConversationIds.length}</small></button>}
              <button className="icon-button" type="button" title="新建对话" aria-label="新建对话" disabled={busy || loadingConversation} onClick={() => void createConversation()}><Plus size={17} /></button>
            </div>
          </div>
          <div className="ai-conversation-list">
            {conversations.map((conversation) => (
              <div className={`ai-conversation-item${conversation.id === conversationId ? ' active' : ''}${selectedConversationIds.includes(conversation.id) ? ' selected' : ''}`} key={conversation.id}>
                {renamingId === conversation.id ? (
                  <div className="ai-conversation-rename">
                    <input autoFocus value={renamingTitle} maxLength={80} onChange={(event) => setRenamingTitle(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') void saveConversationTitle(conversation.id); if (event.key === 'Escape') setRenamingId(undefined) }} />
                    <button type="button" title="保存名称" aria-label="保存名称" onClick={() => void saveConversationTitle(conversation.id)}><Check size={14} /></button>
                    <button type="button" title="取消重命名" aria-label="取消重命名" onClick={() => setRenamingId(undefined)}><X size={14} /></button>
                  </div>
                ) : (
                  <>
                    <input className="batch-checkbox ai-conversation-checkbox" type="checkbox" checked={selectedConversationIds.includes(conversation.id)} disabled={busy || loadingConversation || conversation.runStatus === 'RUNNING'} onChange={() => toggleConversationSelection(conversation.id)} aria-label={`选择对话：${conversation.title}`} />
                    <button className="ai-conversation-select" type="button" disabled={loadingConversation} onClick={() => void openConversation(conversation.id)}>
                      {conversation.runStatus === 'RUNNING' ? <LoaderCircle className="spin" size={14} /> : <MessageSquare size={14} />}
                      <span><strong>{conversation.title}</strong><small>{conversationTime(conversation.updatedAt)}{conversation.runStatus === 'RUNNING' ? ' · 处理中' : conversation.hasPendingDraft ? ' · 待确认' : ''}</small></span>
                    </button>
                    <div className="ai-conversation-actions">
                      <button type="button" title="重命名" aria-label="重命名" onClick={() => { setRenamingId(conversation.id); setRenamingTitle(conversation.title) }}><Pencil size={13} /></button>
                      <button type="button" title={conversation.runStatus === 'RUNNING' ? '处理中，暂时不能删除' : '删除对话'} aria-label="删除对话" disabled={conversation.runStatus === 'RUNNING'} onClick={() => void deleteConversation(conversation.id)}><Trash2 size={13} /></button>
                    </div>
                  </>
                )}
              </div>
            ))}
          </div>
          <button className={`ai-memory-toggle${memoryOpen ? ' active' : ''}`} type="button" onClick={() => void toggleMemories()}><Brain size={16} /><span>长期记忆</span><small>{memories.length || ''}</small></button>
        </aside>

        <section className="ai-chat-workspace">
          <div className="agent-context-bar">
            <span>核心 Agent</span>
            {runStatus && <span className={`agent-run-status status-${runStatus.toLowerCase()}`}>{runStatus.replaceAll('_', ' ')}</span>}
          </div>
          {runSteps.length > 0 && (
            <section className="agent-loop-steps">
              <button type="button" className="agent-loop-steps-toggle" onClick={() => setStepsOpen((open) => !open)} aria-expanded={stepsOpen}>
                <Workflow size={13} />
                <span>运行步骤</span>
                <small>{runSteps.length}</small>
                {stepsOpen ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
              </button>
              {stepsOpen && (
                <ol className="agent-loop-steps-list">
                  {runSteps.map((step) => (
                    <li key={step.seq} className={`agent-loop-step level-${step.stepLevel}`}>
                      <span className={`agent-loop-step-icon status-${step.status.toLowerCase()}`}>{stepStatusIcon(step.status)}</span>
                      <div className="agent-loop-step-body">
                        <div className="agent-loop-step-head">
                          <strong>{step.label}</strong>
                          {step.executorName && step.executorName !== step.label && <em>{step.executorName}</em>}
                          <small>{stepStatusLabel[step.status] ?? step.status}{step.durationMs != null ? ` · ${fmtDuration(step.durationMs)}` : ''}</small>
                        </div>
                        {step.message && <p>{step.message}</p>}
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </section>
          )}
          <div className="ai-chat-messages" ref={messageList} aria-live="polite">
              {messages.map((message, index) => <article className={`ai-chat-message ${message.role}`} key={`${message.role}-${index}`}><span>{message.role === 'assistant' ? 'AI' : '你'}</span><div className="ai-chat-message-body"><p>{message.content}</p>{message.imageUrls?.map((url) => <a className="ai-generated-image-link" href={url} target="_blank" rel="noreferrer" key={url}><img className="ai-generated-image" src={url} alt="AI 生成图片" loading="lazy" /></a>)}</div></article>)}
            {(currentConversationRunning || busy || loadingConversation) && <article className="ai-chat-message assistant loading"><span>AI</span><p><LoaderCircle className="spin" size={14} />{currentConversationRunning ? `后台处理中${pollFailureCount >= 4 ? '，连接后端暂时中断，正在自动重试' : waitSeconds >= 3 ? `，已等待 ${waitSeconds} 秒` : ''}${waitSeconds >= 190 ? '，超过预期时长仍未返回，可稍后刷新查看' : ''}，可切换其他对话` : busy ? '正在处理…' : '正在加载'}</p></article>}
          </div>
          {planReview && !draft && (
            <section className="ai-travel-plan-review">
              <div className="ai-change-heading"><div><strong>旅行计划（待确认）</strong><span>先确认行程，再生成写入草案</span></div><span>可以直接在下方输入修改意见</span></div>
              {travelPlanPreview(planReview)}
              <div className="ai-draft-actions">
                <button className="primary-button" type="button" disabled={busy || currentConversationRunning} onClick={() => void approveTravelPlan()}><Check size={16} />确认行程，生成草案</button>
              </div>
            </section>
          )}
          {draft && (
            <section className="ai-change-draft">
              <div className="ai-change-heading"><div><strong>待确认草案</strong><span>编号：{draft.code}</span></div><span>确认后才会写入计划和日程</span></div>
              {draft.actions.map((action, index) => <article key={`${action.type}-${index}`}>
                <span>{draftActionLabels[action.type] ?? action.type}</span>
                <div className="ai-draft-action-body">
                  <strong>{action.summary || draftActionLabels[action.type] || action.type}</strong>
                  <div className="ai-draft-details">{draftActionDetails(action).map((detail) => <small key={detail}>{detail}</small>)}</div>
                  {draftPlanPreview(action)}
                </div>
              </article>)}
              <div className="ai-draft-actions">
                <button className="primary-button" type="button" disabled={busy} onClick={() => void confirm()}><Check size={16} />确认执行</button>
                <button className="secondary-button" type="button" disabled={busy} onClick={() => void cancel()}><X size={16} />取消草案并修改行程</button>
              </div>
            </section>
          )}
          <div className="ai-composer-zone">
            {attachments.length > 0 && <div className="agent-attachments">{attachments.map((item) => <span className="agent-attachment" key={item.id}><FileText size={14} /><span><strong>{item.fileName}</strong><small>{item.vectorIndexed ? '已索引' : '已解析'}</small></span><button type="button" title="移除附件" aria-label={`移除 ${item.fileName}`} onClick={() => setAttachments((current) => current.filter((file) => file.id !== item.id))}><X size={13} /></button></span>)}</div>}
            <div className="ai-chat-composer">
              <textarea value={input} disabled={Boolean(draft) || currentConversationRunning} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void send() } }} placeholder={draft ? '请先取消草案，再修改旅行行程' : planReview ? '输入修改意见，或点击“确认行程，生成草案”' : currentConversationRunning ? '当前对话正在后台处理，可切换到其他对话' : '输入消息'} rows={1} />
              <div className="ai-composer-actions">
                <input ref={fileInput} type="file" multiple hidden accept=".pdf,.doc,.docx,.txt,.md,.csv,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg" onChange={(event) => void selectFiles(event.target.files)} />
                <button className="icon-button" type="button" disabled={busy || uploading || Boolean(draft) || currentConversationRunning} title="添加附件" aria-label="添加附件" onClick={() => fileInput.current?.click()}>{uploading ? <LoaderCircle className="spin" size={17} /> : <Paperclip size={17} />}</button>
                <button className="icon-button ai-send-button" type="button" disabled={busy || uploading || Boolean(draft) || currentConversationRunning || (!input.trim() && !attachments.length)} title="发送" aria-label="发送" onClick={() => void send()}><Send size={17} /></button>
              </div>
            </div>
          </div>
          {error && <p className="ai-chat-error">{error}</p>}
          {lastChangeSet && <button className="secondary-button agent-undo" type="button" disabled={busy} onClick={() => void undo()}><RefreshCw size={16} /> 撤销上次 AI 变更</button>}
        </section>

        {informationFormOpen && (
          <div className="modal-scrim" role="dialog" aria-modal="true" aria-labelledby="agent-information-form-title">
            <form className="editor-modal agent-information-modal" onSubmit={submitInformationForm}>
              <div className="modal-heading">
                <div><small>{learningInformationForm ? 'AI 学习规划' : 'AI 旅行规划'}</small><h2 id="agent-information-form-title">信息搜集表</h2></div>
                <button className="icon-button" type="button" title="关闭" aria-label="关闭" onClick={() => setInformationFormOpen(false)}><X size={16} /></button>
              </div>
              <p className="agent-information-intro">请补充必要信息，AI 会结合表单和备注生成具体计划。</p>
              <div className="modal-fields">
                {learningInformationForm && <>
                  {informationFieldRequired('goalId') && <label>学习目标 ID<input required value={informationForm.goalId} onChange={(event) => setInformationForm((current) => ({ ...current, goalId: event.target.value }))} placeholder="粘贴学习目标 ID" /></label>}
                  <label>学习目标<input required={informationFieldRequired('title')} value={informationForm.title} onChange={(event) => setInformationForm((current) => ({ ...current, title: event.target.value }))} placeholder="例如：掌握 Java 基础" /></label>
                  <label>学习领域<input required={informationFieldRequired('domain')} value={informationForm.domain} onChange={(event) => setInformationForm((current) => ({ ...current, domain: event.target.value }))} placeholder="例如：编程、英语、数学" /></label>
                  <div className="agent-information-grid"><label>当前基础<input value={informationForm.currentLevel} onChange={(event) => setInformationForm((current) => ({ ...current, currentLevel: event.target.value }))} /></label><label>目标水平<input value={informationForm.targetLevel} onChange={(event) => setInformationForm((current) => ({ ...current, targetLevel: event.target.value }))} /></label></div>
                  <div className="agent-information-grid"><label>目标日期<input required={informationFieldRequired('targetDate')} type="date" value={informationForm.targetDate} onChange={(event) => setInformationForm((current) => ({ ...current, targetDate: event.target.value }))} /></label><label>每周学习时长<input required={informationFieldRequired('weeklyHours')} min="0.5" step="0.5" type="number" value={informationForm.weeklyHours} onChange={(event) => setInformationForm((current) => ({ ...current, weeklyHours: event.target.value }))} /></label></div>
                  <div className="agent-information-grid"><label>可学习日期<input value={informationForm.availableDays} onChange={(event) => setInformationForm((current) => ({ ...current, availableDays: event.target.value }))} placeholder="例如：周一、周三、周日" /></label><label>学习方式<input value={informationForm.learningStyle} onChange={(event) => setInformationForm((current) => ({ ...current, learningStyle: event.target.value }))} placeholder="例如：视频+练习" /></label></div>
                </>}
                {!learningInformationForm && <label>目的地<input required={informationFieldRequired('destination')} value={informationForm.destination} onChange={(event) => setInformationForm((current) => ({ ...current, destination: event.target.value }))} placeholder="例如：山东青岛" /></label>}
                {!learningInformationForm && <label>出发地<input value={informationForm.origin} onChange={(event) => setInformationForm((current) => ({ ...current, origin: event.target.value }))} placeholder="例如：北京" /></label>}
                {!learningInformationForm && <div className="agent-information-grid">
                  <label>出发日期<input required={informationFieldRequired('startDate')} type="date" value={informationForm.startDate} onChange={(event) => setInformationForm((current) => ({ ...current, startDate: event.target.value }))} /></label>
                  <label>结束日期<input required={informationFieldRequired('endDate')} type="date" value={informationForm.endDate} onChange={(event) => setInformationForm((current) => ({ ...current, endDate: event.target.value }))} /></label>
                </div>}
                {!learningInformationForm && <div className="agent-information-grid">
                  <label>出行人数<input required min="1" type="number" value={informationForm.travelers} onChange={(event) => setInformationForm((current) => ({ ...current, travelers: event.target.value }))} /></label>
                  <label>旅行节奏<select value={informationForm.pace} onChange={(event) => setInformationForm((current) => ({ ...current, pace: event.target.value }))}><option value="relaxed">轻松休闲</option><option value="balanced">适中</option><option value="intensive">紧凑充实</option></select></label>
                </div>}
                {!learningInformationForm && <div className="agent-information-grid">
                  <label>预算金额<input min="0" type="number" value={informationForm.budgetAmount} onChange={(event) => setInformationForm((current) => ({ ...current, budgetAmount: event.target.value }))} placeholder="可选" /></label>
                  <label>币种<input maxLength={3} value={informationForm.budgetCurrency} onChange={(event) => setInformationForm((current) => ({ ...current, budgetCurrency: event.target.value.toUpperCase() }))} /></label>
                </div>}
                {!learningInformationForm && <label>兴趣偏好<input value={informationForm.interests} onChange={(event) => setInformationForm((current) => ({ ...current, interests: event.target.value }))} placeholder="例如：海边、美食、历史文化" /></label>}
                <label>备注<textarea rows={4} value={informationForm.remarks} onChange={(event) => setInformationForm((current) => ({ ...current, remarks: event.target.value }))} placeholder="填写其他要求，例如不要早起、多安排海边活动等" /></label>
              </div>
              <div className="modal-actions"><button className="secondary-button" type="button" onClick={() => setInformationFormOpen(false)}>稍后填写</button><button className="primary-button" type="submit"><Check size={16} />提交并生成计划</button></div>
            </form>
          </div>
        )}

        {memoryOpen && (
          <aside className="ai-memory-panel">
            <div className="ai-memory-heading"><div><Brain size={17} /><strong>长期记忆</strong></div><button className="icon-button" type="button" title="关闭记忆" aria-label="关闭记忆" onClick={() => setMemoryOpen(false)}><X size={16} /></button></div>
            <div className="ai-memory-list">
              {!memories.length && <p className="ai-memory-empty">对话中形成的长期偏好会出现在这里。</p>}
              {memories.map((memory) => (
                <article className="ai-memory-item" key={memory.id}>
                  <span>{memoryCategory[memory.category]}</span>
                  {editingMemoryId === memory.id ? (
                    <textarea autoFocus value={editingMemoryText} maxLength={2000} rows={4} onChange={(event) => setEditingMemoryText(event.target.value)} />
                  ) : <p>{memory.content}</p>}
                  <div>
                    {editingMemoryId === memory.id ? <button type="button" title="保存记忆" aria-label="保存记忆" onClick={() => void saveMemory(memory.id)}><Save size={14} /></button> : <button type="button" title="编辑记忆" aria-label="编辑记忆" onClick={() => { setEditingMemoryId(memory.id); setEditingMemoryText(memory.content) }}><Pencil size={14} /></button>}
                    <button type="button" title="删除记忆" aria-label="删除记忆" onClick={() => void deleteMemory(memory.id)}><Trash2 size={14} /></button>
                  </div>
                </article>
              ))}
            </div>
          </aside>
        )}
      </div>
    </div>
  )
}
