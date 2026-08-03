import { useEffect, useMemo, useState } from 'react'
import { format, parseISO } from 'date-fns'
import {
  calendarItems as initialCalendarItems,
  dailyCompletion,
  heatmapValues,
  monthlyHistory,
  notes as initialNotes,
  plans,
  initialTodos,
} from '../mocks/plannerData'
import type { CalendarItem, Note, Plan, PlanItem, TodoItem } from '../types/planner'
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
  daily: dailyCompletion,
  heatmap: heatmapValues.map((cell) => ({ ...cell, id: String(cell.id) })),
  monthly: monthlyHistory,
  metrics: { completion: 81, completed: 82, planned: 101, focusHours: 46.5, streak: 12 },
}

function App() {
  // App 只保留跨页面状态和数据同步；表单和通用视觉组件已下沉到独立模块。
  const [activeView, setActiveView] = useState<View>('calendar')
  const [activePlanId, setActivePlanId] = useState('product')
  const [plansData, setPlansData] = useState<Plan[]>(plans)
  const [calendarItems, setCalendarItems] = useState<CalendarItem[]>(initialCalendarItems)
  const [todoItems, setTodoItems] = useState<TodoItem[]>(initialTodos)
  const [noteItems, setNoteItems] = useState<Note[]>(initialNotes)
  const [statsData, setStatsData] = useState<PlannerStats>(initialStats)
  const [openedSchedule, setOpenedSchedule] = useState<CalendarItem | null>(null)
  const [selectedNoteId, setSelectedNoteId] = useState<string | undefined>()
  const [planDialogOpen, setPlanDialogOpen] = useState(false)
  const [todoDialogOpen, setTodoDialogOpen] = useState(false)
  const [scheduleDialog, setScheduleDialog] = useState<{ date?: string; planId?: string; item?: CalendarItem } | null>(null)
  const [mobileNavOpen, setMobileNavOpen] = useState(false)

  useEffect(() => {
    // 后端是事实来源；初始化和定时刷新失败时，继续展示 mocks 中的可交互预览。
    let active = true
    const refreshData = () => plannerApi.load().then((data) => {
      if (!active) return
      setPlansData(data.plans)
      setCalendarItems(data.schedules)
      setTodoItems(data.todos)
      setNoteItems(data.notes)
      setStatsData(data.stats)
      setActivePlanId((current) => data.plans.some((plan) => plan.id === current) ? current : (data.plans[0]?.id ?? ''))
    }).catch(() => {
      // 数据库未启动时继续使用页面内的预览数据。
    })
    void refreshData()
    const timer = window.setInterval(() => { void refreshData() }, 4000)
    window.addEventListener('focus', refreshData)
    return () => {
      active = false
      window.clearInterval(timer)
      window.removeEventListener('focus', refreshData)
    }
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

  // 先更新界面再提交 API，保证网络延迟不会阻塞日历勾选；失败时下一次刷新会纠正状态。
  const toggleCalendarItem = (id: string) => {
    if (todoItems.some((item) => item.id === id)) {
      setTodoItems((current) => current.map((item) => {
        if (item.id !== id) return item
        const next = { ...item, done: !item.done }
        void plannerApi.updateTodo(next).catch(() => undefined)
        return next
      }))
      return
    }
    setCalendarItems((current) => current.map((item) => {
      if (item.id !== id) return item
      const next = { ...item, status: (item.status === 'done' ? 'pending' : 'done') as CalendarItem['status'] }
      void plannerApi.updateSchedule(next).catch(() => undefined)
      return next
    }))
    setOpenedSchedule((current) => current?.id === id
      ? { ...current, status: current.status === 'done' ? 'pending' : 'done' }
      : current)
  }

  const createPlan = (plan: Plan) => {
    setPlansData((current) => [...current, plan])
    void plannerApi.createPlan(plan).then((created) => {
      setPlansData((current) => current.map((item) => item.id === plan.id ? { ...item, id: created.id } : item))
      setActivePlanId(created.id)
    }).catch(() => undefined)
    setActivePlanId(plan.id)
    setPlanDialogOpen(false)
    setActiveView('plans')
  }

  const updatePlan = (next: Plan) => {
    setPlansData((current) => current.map((plan) => plan.id === next.id ? next : plan))
    void plannerApi.updatePlan(next).catch(() => undefined)
  }

  const deletePlan = (id: string) => {
    void plannerApi.deletePlan(id).catch(() => undefined)
    setPlansData((current) => current.filter((plan) => plan.id !== id))
    setCalendarItems((current) => current.filter((item) => item.planId !== id))
    const next = plansData.find((plan) => plan.id !== id)
    if (next) setActivePlanId(next.id)
  }

  const createStage = (planId: string, item: PlanItem) => {
    setPlansData((current) => current.map((plan) => {
      if (plan.id !== planId) return plan
      const items = [...plan.items, item]
      return { ...plan, items, totalTasks: items.length, progress: Math.round(items.reduce((sum, value) => sum + value.progress, 0) / items.length) }
    }))
    void plannerApi.createPlanStage(planId, item).then((created) => {
      setPlansData((current) => current.map((plan) => plan.id === planId
        ? { ...plan, items: plan.items.map((stage) => stage.id === item.id ? { ...stage, id: created.id } : stage) }
        : plan))
    }).catch(() => undefined)
  }

  const updateStage = (planId: string, item: PlanItem) => {
    setPlansData((current) => current.map((plan) => {
      if (plan.id !== planId) return plan
      const items = plan.items.map((value) => value.id === item.id ? item : value)
      return { ...plan, items, progress: items.length ? Math.round(items.reduce((sum, value) => sum + value.progress, 0) / items.length) : 0, completedTasks: items.filter((value) => value.progress >= 100).length, totalTasks: items.length }
    }))
    void plannerApi.updatePlanStage(planId, item).catch(() => undefined)
  }

  const deleteStage = (planId: string, itemId: string) => {
    setPlansData((current) => current.map((plan) => {
      if (plan.id !== planId) return plan
      const items = plan.items.filter((item) => item.id !== itemId)
      return { ...plan, items, progress: items.length ? Math.round(items.reduce((sum, value) => sum + value.progress, 0) / items.length) : 0, completedTasks: items.filter((value) => value.progress >= 100).length, totalTasks: items.length }
    }))
    void plannerApi.deletePlanStage(planId, itemId).catch(() => undefined)
  }

  const saveSchedule = (item: CalendarItem) => {
    const exists = calendarItems.some((value) => value.id === item.id)
    setCalendarItems((current) => current.some((value) => value.id === item.id)
      ? current.map((value) => value.id === item.id ? item : value)
      : [...current, item])
    if (exists) void plannerApi.updateSchedule(item).catch(() => undefined)
    else void plannerApi.createSchedule(item).then((created) => setCalendarItems((current) => current.map((value) => value.id === item.id ? { ...value, id: created.id } : value))).catch(() => undefined)
    setScheduleDialog(null)
    setActiveView('calendar')
  }

  const deleteSchedule = (id: string) => {
    void plannerApi.deleteSchedule(id).catch(() => undefined)
    setCalendarItems((current) => current.filter((item) => item.id !== id))
    setOpenedSchedule(null)
    setActiveView('calendar')
  }

  const createTodo = (item: TodoItem) => {
    setTodoItems((current) => [item, ...current])
    void plannerApi.createTodo(item).then((created) => setTodoItems((current) => current.map((value) => value.id === item.id ? { ...value, id: created.id } : value))).catch(() => undefined)
  }
  const updateTodo = (item: TodoItem) => {
    setTodoItems((current) => current.map((value) => value.id === item.id ? item : value))
    void plannerApi.updateTodo(item).catch(() => undefined)
  }
  const deleteTodo = (id: string) => {
    setTodoItems((current) => current.filter((item) => item.id !== id))
    void plannerApi.deleteTodo(id).catch(() => undefined)
  }

  const createNote = (category: string) => {
    const next: Note = { id: 'note-' + Date.now(), title: '未命名笔记', category, excerpt: '', updatedAt: '刚刚', color: '#d39a24', relatedIds: [], source: '个人创建' }
    setNoteItems((current) => [next, ...current])
    void plannerApi.createNote(next).then((created) => {
      setNoteItems((current) => current.map((note) => note.id === next.id ? { ...note, id: created.id } : note))
      setSelectedNoteId(created.id)
    }).catch(() => undefined)
    setSelectedNoteId(next.id)
    return next
  }

  const deleteNote = (id: string) => {
    void plannerApi.deleteNote(id).catch(() => undefined)
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
        void plannerApi.createNote(note).then((created) => setNoteItems((current) => current.map((value) => value.id === note.id ? { ...value, id: created.id } : value))).catch(() => undefined)
        return
      }
      if (old !== note) void plannerApi.updateNote(note).catch(() => undefined)
      note.relatedIds.filter((id) => !old.relatedIds.includes(id)).forEach((id) => void plannerApi.createNoteRelation(note.id, id).catch(() => undefined))
      old.relatedIds.filter((id) => !note.relatedIds.includes(id)).forEach((id) => void plannerApi.deleteNoteRelation(note.id, id).catch(() => undefined))
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
        <div className="page-content">
          {activeView === 'calendar' && <CalendarPage items={calendarEntries} plansData={plansData} onToggleItem={toggleCalendarItem} onOpenItem={openSchedule} onAdd={(date) => setScheduleDialog({ date: format(date, 'yyyy-MM-dd') })} />}
          {activeView === 'plans' && <PlansPage activePlanId={activePlanId} plansData={plansData} calendarItems={calendarItems} onPlanChange={setActivePlanId} onUpdatePlan={updatePlan} onDeletePlan={deletePlan} onCreateStage={createStage} onUpdateStage={updateStage} onDeleteStage={deleteStage} onAddSchedule={(planId) => setScheduleDialog({ planId })} />}
          {activeView === 'todos' && <TodosPage todoItems={todoItems} onCreate={createTodo} onUpdate={updateTodo} onDelete={deleteTodo} onToggle={toggleCalendarItem} />}
          {activeView === 'notes' && <NotesPage noteItems={noteItems} onChange={saveNotes} onCreate={createNote} onDelete={deleteNote} selectedNoteId={selectedNoteId} />}
          {activeView === 'stats' && <StatsPage stats={statsData} />}
          {activeView === 'review' && <ReviewPage plansData={plansData} calendarItems={calendarItems} onUpdatePlan={updatePlan} onUpdateSchedule={(item) => {
            setCalendarItems((current) => current.map((value) => value.id === item.id ? item : value))
            void plannerApi.updateSchedule(item).catch(() => undefined)
          }} />}
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
