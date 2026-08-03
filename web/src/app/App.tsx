import { useCallback, useEffect, useMemo, useState } from 'react'
import { format, parseISO } from 'date-fns'
import type { CalendarItem, Note, Plan, PlanItem, PlanTask, TodoItem } from '../types/planner'
import '../styles/app.css'
import { plannerApi, type PlannerStats } from '../services/plannerApi'
import { PlanDialog, ScheduleDialog, TodoDialog } from '../components/dialogs/PlannerDialogs'
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
import { ReviewPage } from '../features/review/ReviewPage'
const initialStats: PlannerStats = {
  daily: [],
  heatmap: [],
  monthly: [],
  metrics: { completion: 0, completed: 0, planned: 0, focusHours: 0, streak: 0 },
}

function App() {
  // App 只保留跨页面状态和数据同步；表单和通用视觉组件已下沉到独立模块。
  const [activeView, setActiveView] = useState<View>('calendar')
  const [activePlanId, setActivePlanId] = useState('product')
  const [plansData, setPlansData] = useState<Plan[]>([])
  const [calendarItems, setCalendarItems] = useState<CalendarItem[]>([])
  const [todoItems, setTodoItems] = useState<TodoItem[]>([])
  const [noteItems, setNoteItems] = useState<Note[]>([])
  const [statsData, setStatsData] = useState<PlannerStats>(initialStats)
  const [openedSchedule, setOpenedSchedule] = useState<CalendarItem | null>(null)
  const [selectedNoteId, setSelectedNoteId] = useState<string | undefined>()
  const [planDialogOpen, setPlanDialogOpen] = useState(false)
  const [todoDialogOpen, setTodoDialogOpen] = useState(false)
  const [scheduleDialog, setScheduleDialog] = useState<{ date?: string; planId?: string; item?: CalendarItem } | null>(null)
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const [dataError, setDataError] = useState('')
  const [dataRevision, setDataRevision] = useState(0)
  const [reviewSeed, setReviewSeed] = useState('')

  const refreshData = useCallback(async () => {
    try {
      const data = await plannerApi.load()
      setPlansData(data.plans)
      setCalendarItems(data.schedules)
      setTodoItems(data.todos)
      setNoteItems(data.notes)
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
    ...plansData.map((plan) => ({ id: plan.id, type: 'plan' as const, title: plan.title, meta: '长期计划 · ' + plan.progress + '%' })),
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
    stats: { title: '计划统计', subtitle: '2026 年 8 月执行情况' },
    review: { title: 'AI 复盘', subtitle: '基于真实完成记录' },
    schedule: { title: '安排详情', subtitle: '把资料、学习和笔记放在一起' },
  }

  const openPlan = (planId: string) => {
    setActivePlanId(planId)
    setActiveView('plans')
    setMobileNavOpen(false)
  }

  const openSchedule = (item: CalendarItem) => {
    if (item.kind === 'todo') {
      setActiveView('todos')
      setMobileNavOpen(false)
      return
    }
    setOpenedSchedule(item)
    setActiveView('schedule')
  }

  const changeView = (view: View) => {
    setActiveView(view)
    if (view !== 'schedule') setOpenedSchedule(null)
  }

  const mutation = async (work: Promise<unknown>) => {
    setDataError('')
    try { await work; await refreshData() }
    catch (cause) { setDataError(cause instanceof Error ? cause.message : '保存失败') }
  }

  const toggleCalendarItem = (id: string) => {
    const todo = todoItems.find((item) => item.id === id)
    if (todo) { void mutation(plannerApi.updateTodo({ ...todo, done: !todo.done })); return }
    const schedule = calendarItems.find((item) => item.id === id)
    if (schedule) void mutation(plannerApi.updateSchedule({ ...schedule, status: schedule.status === 'done' ? 'pending' : 'done' }))
  }

  const createPlan = async (plan: Plan) => {
    setDataError('')
    try { const created = await plannerApi.createPlan(plan); await refreshData(); setActivePlanId(created.id); setPlanDialogOpen(false); setActiveView('plans') }
    catch (cause) { setDataError(cause instanceof Error ? cause.message : '计划创建失败') }
  }

  const updatePlan = (next: Plan) => { void mutation(plannerApi.updatePlan(next)) }
  const deletePlan = (id: string) => { if (window.confirm('删除后将进入回收站 30 天，确认继续？')) void mutation(plannerApi.deletePlan(id)) }
  const createStage = (planId: string, item: PlanItem) => { void mutation(plannerApi.createPlanStage(planId, item)) }
  const updateStage = (planId: string, item: PlanItem) => { void mutation(plannerApi.updatePlanStage(planId, item)) }
  const deleteStage = (planId: string, item: PlanItem) => { if (window.confirm('阶段及其任务将进入回收站，确认继续？')) void mutation(plannerApi.deletePlanStage(planId, item.id, item.version)) }

  const createTask = (planId: string, stageId: string, fields: { title: string; estimatedMinutes?: number; dueAt?: string }) => {
    void mutation(plannerApi.createTask({ planId, stageId, title: fields.title, estimatedMinutes: fields.estimatedMinutes, dueAt: fields.dueAt, priority: 'medium' }))
  }
  const updateTask = (task: PlanTask) => { void mutation(plannerApi.updateTask(task)) }
  const taskAction = async (task: PlanTask, action: 'complete' | 'delay' | 'block' | 'skip' | 'cancel' | 'reopen', fields: Record<string, unknown> = {}) => {
    setDataError('')
    try {
      const result = await plannerApi.taskAction(task, action, fields)
      await refreshData()
      return { ...task, ...result } as PlanTask
    } catch (cause) { setDataError(cause instanceof Error ? cause.message : '任务状态更新失败') }
  }
  const deleteTask = (task: PlanTask) => { if (window.confirm('任务将进入回收站 30 天，确认继续？')) void mutation(plannerApi.deleteTask(task)) }

  const saveSchedule = async (item: CalendarItem) => {
    setDataError('')
    try { if (calendarItems.some((value) => value.id === item.id)) await plannerApi.updateSchedule(item); else await plannerApi.createSchedule(item); await refreshData(); setScheduleDialog(null); setActiveView('calendar') }
    catch (cause) { setDataError(cause instanceof Error ? cause.message : '日程保存失败') }
  }
  const deleteSchedule = (id: string) => { if (window.confirm('日程将进入回收站 30 天，确认继续？')) void mutation(plannerApi.deleteSchedule(id)).then(() => { setOpenedSchedule(null); setActiveView('calendar') }) }
  const createTodo = (item: TodoItem) => { void mutation(plannerApi.createTodo(item)) }
  const updateTodo = (item: TodoItem) => { void mutation(plannerApi.updateTodo(item)) }
  const deleteTodo = (id: string) => { if (window.confirm('待办将进入回收站 30 天，确认继续？')) void mutation(plannerApi.deleteTodo(id)) }

  const createNote = (category: string) => {
    const next: Note = { id: 'note-' + Date.now(), title: '未命名笔记', category, excerpt: '', updatedAt: '刚刚', color: '#d39a24', relatedIds: [], source: '个人创建' }
    setNoteItems((current) => [next, ...current])
    void plannerApi.createNote(next).then((created) => {
      setNoteItems((current) => current.map((note) => note.id === next.id ? { ...note, id: created.id } : note))
      setSelectedNoteId(created.id)
    }).catch((cause) => setDataError(cause instanceof Error ? cause.message : '笔记创建失败'))
    setSelectedNoteId(next.id)
    return next
  }

  const deleteNote = (id: string) => {
    void plannerApi.deleteNote(id).catch((cause) => setDataError(cause instanceof Error ? cause.message : '笔记删除失败'))
    setNoteItems((current) => {
      if (current.length <= 1) return current
      return current.filter((note) => note.id !== id).map((note) => ({ ...note, relatedIds: note.relatedIds.filter((relatedId) => relatedId !== id) }))
    })
  }

  const saveNotes = (nextNotes: Note[]) => {
    const previous = noteItems
    setNoteItems(nextNotes)
    nextNotes.forEach((note) => {
      const old = previous.find((value) => value.id === note.id)
      if (!old) {
        void plannerApi.createNote(note).then((created) => setNoteItems((current) => current.map((value) => value.id === note.id ? { ...value, id: created.id } : value))).catch((cause) => setDataError(cause instanceof Error ? cause.message : '笔记创建失败'))
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
      setActiveView('todos')
      return
    }
    if (item.type === 'note') {
      setSelectedNoteId(item.id)
      setActiveView('notes')
      return
    }
    const schedule = calendarItems.find((value) => value.id === item.id)
    if (schedule) openSchedule(schedule)
  }

  return (
    <div className="app-shell">
      <Sidebar
        activeView={activeView}
        plansData={plansData}
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
          {activeView === 'calendar' && <CalendarPage items={calendarEntries} plansData={plansData} onToggleItem={toggleCalendarItem} onOpenItem={openSchedule} onAdd={(date) => setScheduleDialog({ date: format(date, 'yyyy-MM-dd') })} />}
          {activeView === 'plans' && <PlansPage activePlanId={activePlanId} plansData={plansData} calendarItems={calendarItems} onPlanChange={setActivePlanId} onUpdatePlan={updatePlan} onDeletePlan={deletePlan} onCreateStage={createStage} onUpdateStage={updateStage} onDeleteStage={deleteStage} onCreateTask={createTask} onUpdateTask={updateTask} onTaskAction={taskAction} onDeleteTask={deleteTask} onAddSchedule={(planId) => setScheduleDialog({ planId })} onAiAdjust={(plan) => { setReviewSeed(`请调整计划“${plan.title}”（计划 ID：${plan.id}）`); setActiveView('review') }} />}
          {activeView === 'todos' && <TodosPage todoItems={todoItems} onCreate={createTodo} onUpdate={updateTodo} onDelete={deleteTodo} onToggle={toggleCalendarItem} />}
          {activeView === 'notes' && <NotesPage noteItems={noteItems} onChange={saveNotes} onCreate={createNote} onDelete={deleteNote} selectedNoteId={selectedNoteId} />}
          {activeView === 'stats' && <StatsPage stats={statsData} />}
          {activeView === 'review' && <ReviewPage seed={reviewSeed} onDataChanged={() => setDataRevision((value) => value + 1)} />}
          {activeView === 'schedule' && openedSchedule && (
            <ScheduleDetailPage item={openedSchedule} plansData={plansData} noteItems={noteItems} onNotesChange={saveNotes} onToggle={toggleCalendarItem} onEdit={(item) => setScheduleDialog({ item })} onDelete={deleteSchedule} onBack={() => changeView('calendar')} />
          )}
        </div>
      </main>
      {planDialogOpen && <PlanDialog onClose={() => setPlanDialogOpen(false)} onSubmit={createPlan} />}
      {todoDialogOpen && <TodoDialog onClose={() => setTodoDialogOpen(false)} onSubmit={(item) => { createTodo(item); setTodoDialogOpen(false); setActiveView('todos') }} />}
      {scheduleDialog && <ScheduleDialog plansData={plansData} initialItem={scheduleDialog.item} defaultDate={scheduleDialog.date} defaultPlanId={scheduleDialog.planId} onClose={() => setScheduleDialog(null)} onSubmit={saveSchedule} />}
    </div>
  )
}

export default App
