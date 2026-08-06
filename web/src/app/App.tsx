import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { endOfWeek, format, isWithinInterval, parseISO, startOfWeek } from 'date-fns'
import { Bell, X } from 'lucide-react'
import type { CalendarItem, Note, Plan, PlanItem, PlanTask, TodoItem } from '../types/planner'
import '../styles/app.css'
import { plannerApi, type PlannerStats, type TodoReminder, type TrashItem } from '../services/plannerApi'
import { ConfirmDeleteDialog, PlanDialog, ScheduleDialog, TodoDialog, type NoteDraftFields, type TaskDraftFields } from '../components/dialogs/PlannerDialogs'
import {
  type GlobalSearchItem,
  type NotificationEntry,
  type View,
} from './navigation'

import { AppHeader, Sidebar } from '../components/layout/AppLayout'
import { CalendarPage } from '../features/calendar/CalendarPage'
import { PlansPage } from '../features/plans/PlansPage'
import { TodosPage } from '../features/todos/TodosPage'
import { NotesPage } from '../features/notes/NotesPage'
import { ScheduleDetailPage } from '../features/schedule/ScheduleDetailPage'
import { StatsPage } from '../features/stats/StatsPage'
import { AgentPage } from '../features/agent/AgentPage'
import { ReviewPage } from '../features/review/ReviewPage'
import { TrashPage } from '../features/trash/TrashPage'
const initialStats: PlannerStats = {
  daily: [],
  heatmap: [],
  monthly: [],
  metrics: { completion: 0, completed: 0, planned: 0, focusHours: 0, streak: 0 },
}

const routeViews = new Set<View>(['calendar', 'plans', 'todos', 'notes', 'stats', 'agent', 'review', 'trash', 'schedule'])

function readRoute() {
  const value = window.location.hash.replace(/^#\/?/, '')
  const [view, scheduleId] = value.split('/')
  if (!routeViews.has(view as View)) return { view: 'calendar' as View, scheduleId: null }
  return { view: view as View, scheduleId: view === 'schedule' ? scheduleId || null : null }
}

function routeHash(view: View, scheduleId?: string | null) {
  return view === 'schedule' && scheduleId ? `#/schedule/${encodeURIComponent(scheduleId)}` : `#/${view}`
}

function App() {
  // App 只保留跨页面状态和数据同步；表单和通用视觉组件已下沉到独立模块。
  const initialRoute = readRoute()
  const [activeView, setActiveView] = useState<View>(initialRoute.view)
  const [activePlanId, setActivePlanId] = useState('product')
  const [plansData, setPlansData] = useState<Plan[]>([])
  const [calendarItems, setCalendarItems] = useState<CalendarItem[]>([])
  const [todoItems, setTodoItems] = useState<TodoItem[]>([])
  const [noteItems, setNoteItems] = useState<Note[]>([])
  const [trashItems, setTrashItems] = useState<TrashItem[]>([])
  const [statsData, setStatsData] = useState<PlannerStats>(initialStats)
  const [openedScheduleId, setOpenedScheduleId] = useState<string | null>(initialRoute.scheduleId)
  const [selectedNoteId, setSelectedNoteId] = useState<string | undefined>()
  const [planDialogOpen, setPlanDialogOpen] = useState(false)
  const [todoDialogOpen, setTodoDialogOpen] = useState(false)
  const [scheduleDialog, setScheduleDialog] = useState<{ date?: string; planId?: string; item?: CalendarItem } | null>(null)
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const [dataError, setDataError] = useState('')
  const [dataRevision, setDataRevision] = useState(0)
  const [agentSeed, setAgentSeed] = useState('')
  const [reminderQueue, setReminderQueue] = useState<TodoReminder[]>([])
  const [restoringTrashId, setRestoringTrashId] = useState<string | null>(null)
  const [deleteConfirmation, setDeleteConfirmation] = useState<{ message: string; resolve: (confirmed: boolean) => void } | null>(null)
  const seenReminderIds = useRef(new Set<string>())

  const openedSchedule = useMemo(
    () => openedScheduleId ? calendarItems.find((item) => item.id === openedScheduleId) ?? null : null,
    [calendarItems, openedScheduleId],
  )

  const navigate = useCallback((view: View, scheduleId?: string | null) => {
    const nextHash = routeHash(view, scheduleId)
    if (window.location.hash !== nextHash) window.history.pushState(null, '', nextHash)
    setActiveView(view)
    setOpenedScheduleId(view === 'schedule' ? scheduleId ?? null : null)
  }, [])

  useEffect(() => {
    const syncRoute = () => {
      const route = readRoute()
      setActiveView(route.view)
      setOpenedScheduleId(route.scheduleId)
    }
    window.addEventListener('popstate', syncRoute)
    window.addEventListener('hashchange', syncRoute)
    if (!window.location.hash) window.history.replaceState(null, '', '#/calendar')
    return () => {
      window.removeEventListener('popstate', syncRoute)
      window.removeEventListener('hashchange', syncRoute)
    }
  }, [])

  const refreshData = useCallback(async () => {
    try {
      const data = await plannerApi.load()
      setPlansData(data.plans)
      setCalendarItems(data.schedules)
      setTodoItems(data.todos)
      setNoteItems(data.notes)
      setTrashItems(data.trash)
      setStatsData(data.stats)
      setDataError('')
      setActivePlanId((current) => data.plans.some((plan) => plan.id === current) ? current : (data.plans[0]?.id ?? ''))
    } catch (cause) {
      setDataError(cause instanceof Error ? cause.message : '无法读取真实数据，请检查后端和数据库连接。')
    }
  }, [])

  useEffect(() => {
    // 后端是唯一事实来源；仅在进入页面、操作成功或窗口重新聚焦时刷新。
    void refreshData()
    window.addEventListener('focus', refreshData)
    return () => {
      window.removeEventListener('focus', refreshData)
    }
  }, [dataRevision, refreshData])

  useEffect(() => {
    // 网页端每 30 秒领取一次到期提醒；接口已做服务端去重，刷新页面不会重复弹出。
    let stopped = false
    const pollReminders = async () => {
      try {
        const reminders = await plannerApi.loadDueReminders()
        if (stopped) return
        const fresh = reminders.filter((item) => !seenReminderIds.current.has(item.id))
        if (fresh.length === 0) return
        fresh.forEach((item) => seenReminderIds.current.add(item.id))
        setReminderQueue((current) => [...current, ...fresh])
        if ('Notification' in window && Notification.permission === 'granted') {
          fresh.forEach((item) => new Notification('长路计划提醒', { body: item.title }))
        }
      } catch {
        // 提醒轮询失败时下次继续重试，不阻塞计划、待办等主数据加载。
      }
    }
    void pollReminders()
    const timer = window.setInterval(() => void pollReminders(), 30_000)
    return () => { stopped = true; window.clearInterval(timer) }
  }, [])

  // 待办安排到日期后映射成日历条目，但不复制成另一份可持久化数据。
  const calendarEntries = useMemo<CalendarItem[]>(() => [
    ...calendarItems,
    ...todoItems.filter((todo) => todo.date).map((todo) => ({
      id: todo.id,
      date: todo.date,
      title: todo.title,
      time: todo.time,
      color: '#b85f42',
      status: (todo.done ? 'done' : 'pending') as CalendarItem['status'],
      duration: 15,
      kind: 'todo' as const,
      priority: todo.priority,
      reminder: todo.reminder,
    })),
  ], [calendarItems, todoItems])

  const searchItems = useMemo<GlobalSearchItem[]>(() => [
    ...plansData.map((plan) => ({ id: plan.id, type: 'plan' as const, title: plan.title, meta: '长期计划 · ' + plan.taskProgress + '%' })),
    ...todoItems.map((todo) => ({ id: todo.id, type: 'todo' as const, title: todo.title, meta: (todo.date ? format(parseISO(todo.date), 'M月d日') + ' · ' + todo.time : '未安排') + ' · ' + todo.priority + '优先级' })),
    ...noteItems.map((note) => ({ id: note.id, type: 'note' as const, title: note.title, meta: note.category + ' · ' + (note.source ?? '个人笔记') })),
    ...calendarItems.map((item) => ({ id: item.id, type: 'schedule' as const, title: item.title, meta: format(parseISO(item.date), 'M月d日') + ' · ' + item.time })),
  ], [plansData, todoItems, noteItems, calendarItems])

  const notifications = useMemo<NotificationEntry[]>(() => calendarEntries
    .filter((item) => item.date === format(new Date(), 'yyyy-MM-dd') && item.status !== 'done')
    .slice(0, 6)
    .map((item) => ({
      id: item.id,
      type: item.kind === 'todo' ? 'todo' as const : 'schedule' as const,
      title: item.title,
      meta: item.kind === 'todo' ? (item.reminder ?? '一次性待办') : '今日日程',
      time: item.time,
    })), [calendarEntries])

  const pageMeta: Record<View, { title: string; subtitle: string }> = {
    calendar: { title: '计划日历', subtitle: '长期目标，落到每一天' },
    plans: { title: '长期计划', subtitle: '看见进度，也看见下一步' },
    todos: { title: '待办事项', subtitle: '只需要完成一次的事' },
    notes: { title: '长期笔记', subtitle: '持续积累并连接你的知识' },
    stats: { title: '计划统计', subtitle: `${format(new Date(), 'yyyy 年 M 月')}执行情况` },
    agent: { title: 'AI 对话', subtitle: '让 Agent 帮你推进计划' },
    review: { title: 'AI 复盘', subtitle: '基于真实完成记录' },
    trash: { title: '回收站', subtitle: '恢复 30 天内删除的数据' },
    schedule: { title: '安排详情', subtitle: '把资料、学习和笔记放在一起' },
  }

  const openPlan = (planId: string) => {
    setActivePlanId(planId)
    navigate('plans')
    setMobileNavOpen(false)
  }

  const openSchedule = (item: CalendarItem) => {
    if (item.kind === 'todo') {
      navigate('todos')
      setMobileNavOpen(false)
      return
    }
    navigate('schedule', item.id)
  }

  const changeView = (view: View) => {
    navigate(view)
  }

  const mutation = async (work: Promise<unknown>): Promise<boolean> => {
    setDataError('')
    try { await work; await refreshData(); return true }
    catch (cause) { setDataError(cause instanceof Error ? cause.message : '保存失败'); return false }
  }

  const confirmDelete = (message: string) => new Promise<boolean>((resolve) => {
    setDeleteConfirmation({ message, resolve })
  })

  const bulkDelete = async (label: string, count: number, createRequests: () => Promise<unknown>[]) => {
    if (count === 0 || !await confirmDelete(`确认是否删除已选中的 ${count} 项${label}？删除后将进入回收站 30 天。`)) return false
    setDataError('')
    const results = await Promise.allSettled(createRequests())
    await refreshData()
    const failed = results.filter((result) => result.status === 'rejected').length
    if (failed > 0) {
      setDataError(`${failed} 项${label}删除失败，请重试。`)
      return false
    }
    return true
  }

  const toggleCalendarItem = (id: string) => {
    const todo = todoItems.find((item) => item.id === id)
    if (todo) { void mutation(plannerApi.updateTodo({ ...todo, done: !todo.done })); return }
    const schedule = calendarItems.find((item) => item.id === id)
    if (schedule) void mutation(plannerApi.updateSchedule({ ...schedule, status: schedule.status === 'done' ? 'pending' : 'done' }))
  }

  const createPlan = async (plan: Plan) => {
    setDataError('')
    try { const created = await plannerApi.createPlan(plan); await refreshData(); setActivePlanId(created.id); setPlanDialogOpen(false); navigate('plans'); return true }
    catch (cause) { setDataError(cause instanceof Error ? cause.message : '计划创建失败'); return false }
  }

  const updatePlan = (next: Plan) => mutation(plannerApi.updatePlan(next))
  const deletePlans = (ids: string[]) => bulkDelete('计划', ids.length, () => ids.map((id) => plannerApi.deletePlan(id)))
  const createStage = (planId: string, item: PlanItem) => { void mutation(plannerApi.createPlanStage(planId, item)) }
  const updateStage = (planId: string, item: PlanItem) => { void mutation(plannerApi.updatePlanStage(planId, item)) }
  const deleteStage = (planId: string, item: PlanItem) => { void confirmDelete('确认是否删除这个阶段？阶段及其任务将进入回收站。').then((confirmed) => { if (confirmed) void mutation(plannerApi.deletePlanStage(planId, item.id, item.version)) }) }

  const createTask = (planId: string, stageId: string, fields: TaskDraftFields) =>
    mutation(plannerApi.createTask({
      planId,
      stageId,
      title: fields.title,
      estimatedMinutes: fields.estimatedMinutes,
      dueAt: fields.dueAt,
      recurrenceType: fields.recurrenceType,
      scheduleStartDate: fields.scheduleStartDate,
      recurrenceEndDate: fields.recurrenceEndDate,
      scheduledTime: fields.scheduledTime,
      priority: 'medium',
    }))
  const updateTask = (task: PlanTask) => mutation(plannerApi.updateTask(task))
  const deleteTask = (task: PlanTask) => { void confirmDelete('确认是否删除这个任务？任务将进入回收站 30 天。').then((confirmed) => { if (confirmed) void mutation(plannerApi.deleteTask(task)) }) }
  const deleteTasks = (tasks: PlanTask[]) => bulkDelete('任务', tasks.length, () => tasks.map((task) => plannerApi.deleteTask(task)))

  const saveSchedule = async (item: CalendarItem) => {
    setDataError('')
    try { if (calendarItems.some((value) => value.id === item.id)) await plannerApi.updateSchedule(item); else await plannerApi.createSchedule(item); await refreshData(); setScheduleDialog(null); navigate('calendar') }
    catch (cause) { setDataError(cause instanceof Error ? cause.message : '日程保存失败') }
  }
  const deleteSchedule = (id: string) => { void confirmDelete('确认是否删除这条日程？日程将进入回收站 30 天。').then((confirmed) => { if (confirmed) void mutation(plannerApi.deleteSchedule(id)).then(() => navigate('calendar')) }) }
  const deleteSchedules = (ids: string[]) => bulkDelete('日程', ids.length, () => [plannerApi.deleteSchedules(ids)])
  const createTodo = (item: TodoItem) => { void mutation(plannerApi.createTodo(item)) }
  const updateTodo = (item: TodoItem) => { void mutation(plannerApi.updateTodo(item)) }
  const deleteTodo = (id: string) => { void confirmDelete('确认是否删除这个待办？待办将进入回收站 30 天。').then((confirmed) => { if (confirmed) void mutation(plannerApi.deleteTodo(id)) }) }
  const deleteTodos = (ids: string[]) => bulkDelete('待办', ids.length, () => ids.map((id) => plannerApi.deleteTodo(id)))
  const restoreTrashItem = async (item: TrashItem) => {
    setRestoringTrashId(item.id)
    try { await mutation(plannerApi.restoreTrashItem(item)) }
    finally { setRestoringTrashId(null) }
  }
  const runTrashBatch = async (items: TrashItem[], action: 'restore' | 'purge') => {
    if (items.length === 0) return false
    if (action === 'purge' && !await confirmDelete(`确认彻底删除选中的 ${items.length} 项记录？删除后无法恢复。`)) return false
    setDataError('')
    const results = await Promise.allSettled(items.map((item) => action === 'restore'
      ? plannerApi.restoreTrashItem(item)
      : plannerApi.purgeTrashItem(item)))
    await refreshData()
    const failed = results.filter((result) => result.status === 'rejected').length
    if (failed > 0) {
      setDataError(`${failed} 项记录操作失败，请刷新后重试。`)
      return false
    }
    return true
  }
  const activeReminder = reminderQueue[0]
  const dismissReminder = () => setReminderQueue((current) => current.slice(1))
  const openReminder = () => { navigate('todos'); dismissReminder() }

  const createNote = (fields: NoteDraftFields) => {
    const next: Note = { id: 'note-' + Date.now(), title: fields.title, category: fields.category, excerpt: fields.content, content: fields.content, updatedAt: '刚刚', color: '#d39a24', relatedIds: [], source: '个人创建' }
    setNoteItems((current) => [next, ...current])
    void plannerApi.createNote(next).then((created) => {
      setNoteItems((current) => current.map((note) => note.id === next.id ? { ...note, id: created.id } : note))
      setSelectedNoteId(created.id)
    }).catch((cause) => setDataError(cause instanceof Error ? cause.message : '笔记创建失败'))
    setSelectedNoteId(next.id)
    return next
  }

  const deleteNote = (id: string) => {
    return confirmDelete('确认是否删除这条笔记？删除后将无法在笔记列表中继续查看。').then((confirmed) => {
      if (!confirmed) return false
      if (!id.startsWith('note-') && !id.startsWith('source-note-') && !id.startsWith('study-note-')) {
        void plannerApi.deleteNote(id).catch((cause) => setDataError(cause instanceof Error ? cause.message : '笔记删除失败'))
      }
      setNoteItems((current) => current.filter((note) => note.id !== id).map((note) => ({ ...note, relatedIds: note.relatedIds.filter((relatedId) => relatedId !== id) })))
      return true
    })
  }
  const deleteNotes = (ids: string[]) => bulkDelete('笔记', ids.length, () => ids.map((id) => plannerApi.deleteNote(id)))

  const saveNotes = (nextNotes: Note[]) => {
    const previous = noteItems
    setNoteItems(nextNotes)
    nextNotes.forEach((note) => {
      const old = previous.find((value) => value.id === note.id)
      if (!old) {
        void plannerApi.createNote(note).then((created) => setNoteItems((current) => {
          if (!current.some((value) => value.id === note.id)) {
            void plannerApi.deleteNote(created.id)
            return current
          }
          return current.map((value) => value.id === note.id ? { ...value, id: created.id } : value)
        })).catch((cause) => setDataError(cause instanceof Error ? cause.message : '笔记创建失败'))
        return
      }
      if (old !== note) void plannerApi.updateNote(note).catch((cause) => setDataError(cause instanceof Error ? cause.message : '笔记保存失败'))
      note.relatedIds.filter((id) => !old.relatedIds.includes(id)).forEach((id) => void plannerApi.createNoteRelation(note.id, id).catch((cause) => setDataError(cause instanceof Error ? cause.message : '关联创建失败')))
      old.relatedIds.filter((id) => !note.relatedIds.includes(id)).forEach((id) => void plannerApi.deleteNoteRelation(note.id, id).catch((cause) => setDataError(cause instanceof Error ? cause.message : '关联删除失败')))
    })
  }

  const createFromHeader = (kind: 'todo' | 'schedule') => {
    if (kind === 'todo') {
      setTodoDialogOpen(true)
      return
    }
    setScheduleDialog({})
  }

  const openGlobalItem = (item: GlobalSearchItem) => {
    if (item.type === 'plan') {
      openPlan(item.id)
      return
    }
    if (item.type === 'todo') {
      navigate('todos')
      return
    }
    if (item.type === 'note') {
      setSelectedNoteId(item.id)
      navigate('notes')
      return
    }
    const schedule = calendarItems.find((value) => value.id === item.id)
    if (schedule) openSchedule(schedule)
  }

  const weeklyCompletion = useMemo(() => {
    const interval = {
      start: startOfWeek(new Date(), { weekStartsOn: 1 }),
      end: endOfWeek(new Date(), { weekStartsOn: 1 }),
    }
    const totals = statsData.heatmap.reduce((result, item) => {
      if (!isWithinInterval(parseISO(item.id), interval)) return result
      result.planned += item.planned ?? 0
      result.completed += item.completed ?? 0
      return result
    }, { planned: 0, completed: 0 })
    return totals.planned === 0 ? 0 : Math.round(totals.completed * 100 / totals.planned)
  }, [statsData.heatmap])

  return (
    <div className="app-shell">
      <Sidebar
        activeView={activeView}
        plansData={plansData}
        weeklyCompletion={weeklyCompletion}
        onViewChange={changeView}
        onPlanOpen={openPlan}
        onCreatePlan={() => setPlanDialogOpen(true)}
        mobileOpen={mobileNavOpen}
        onMobileClose={() => setMobileNavOpen(false)}
      />
      <main className="main-area">
        <AppHeader
          title={pageMeta[activeView].title}
          subtitle={pageMeta[activeView].subtitle}
          onMenu={() => setMobileNavOpen(true)}
          onCreateChoice={createFromHeader}
          searchItems={searchItems}
          notifications={notifications}
          onOpenItem={openGlobalItem}
        />
        {dataError && <p className="ai-chat-error">{dataError}</p>}
        <div className="page-content">
          {activeView === 'calendar' && <CalendarPage items={calendarEntries} plansData={plansData} onToggleItem={toggleCalendarItem} onOpenItem={openSchedule} onDeleteMany={deleteSchedules} onAdd={(date) => setScheduleDialog({ date: format(date, 'yyyy-MM-dd') })} />}
          {activeView === 'plans' && <PlansPage activePlanId={activePlanId} plansData={plansData} calendarItems={calendarItems} onPlanChange={setActivePlanId} onUpdatePlan={updatePlan} onDeletePlans={deletePlans} onCreateStage={createStage} onUpdateStage={updateStage} onDeleteStage={deleteStage} onCreateTask={createTask} onUpdateTask={updateTask} onDeleteTask={deleteTask} onDeleteTasks={deleteTasks} onAddSchedule={(planId) => setScheduleDialog({ planId })} onOpenSchedule={openSchedule} onAiAdjust={(plan) => { setAgentSeed(`请调整计划“${plan.title}”（计划 ID：${plan.id}）`); navigate('agent') }} />}
          {activeView === 'todos' && <TodosPage todoItems={todoItems} onCreate={createTodo} onUpdate={updateTodo} onDelete={deleteTodo} onDeleteMany={deleteTodos} onToggle={toggleCalendarItem} />}
          {activeView === 'notes' && <NotesPage noteItems={noteItems} onChange={saveNotes} onCreate={createNote} onDelete={deleteNote} onDeleteMany={deleteNotes} selectedNoteId={selectedNoteId} />}
          {activeView === 'stats' && <StatsPage stats={statsData} />}
          {activeView === 'agent' && <AgentPage seed={agentSeed} onDataChanged={() => setDataRevision((value) => value + 1)} />}
          {activeView === 'review' && <ReviewPage />}
          {activeView === 'trash' && <TrashPage items={trashItems} restoringId={restoringTrashId} onRestore={(item) => void restoreTrashItem(item)} onRestoreMany={(items) => runTrashBatch(items, 'restore')} onDeleteMany={(items) => runTrashBatch(items, 'purge')} />}
          {activeView === 'schedule' && openedSchedule && (
            <ScheduleDetailPage item={openedSchedule} plansData={plansData} noteItems={noteItems} onNotesChange={saveNotes} onDeleteNote={deleteNote} onOpenNote={(id) => { setSelectedNoteId(id); navigate('notes') }} onToggle={toggleCalendarItem} onEdit={(item) => setScheduleDialog({ item })} onDelete={deleteSchedule} onBack={() => changeView('calendar')} />
          )}
        </div>
      </main>
      {planDialogOpen && <PlanDialog onClose={() => setPlanDialogOpen(false)} onSubmit={createPlan} />}
      {todoDialogOpen && <TodoDialog onClose={() => setTodoDialogOpen(false)} onSubmit={(item) => { createTodo(item); setTodoDialogOpen(false); navigate('todos') }} />}
      {scheduleDialog && <ScheduleDialog plansData={plansData} initialItem={scheduleDialog.item} defaultDate={scheduleDialog.date} defaultPlanId={scheduleDialog.planId} onClose={() => setScheduleDialog(null)} onSubmit={saveSchedule} />}
      {deleteConfirmation && <ConfirmDeleteDialog message={deleteConfirmation.message} onClose={() => { deleteConfirmation.resolve(false); setDeleteConfirmation(null) }} onConfirm={() => { deleteConfirmation.resolve(true); setDeleteConfirmation(null) }} />}
      {activeReminder && (
        <section className="reminder-toast" role="alert" aria-live="assertive">
          <div className="reminder-toast-heading"><Bell size={17} /><strong>待办提醒</strong><button className="icon-button" type="button" title="关闭提醒" onClick={dismissReminder}><X size={15} /></button></div>
          <p>{activeReminder.title}</p>
          <small>截止时间：{activeReminder.dueAt.replace('T', ' ').slice(0, 16)}</small>
          <button className="primary-button" type="button" onClick={openReminder}>查看待办</button>
        </section>
      )}
    </div>
  )
}

export default App
