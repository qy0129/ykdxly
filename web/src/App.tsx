import { useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type WheelEvent as ReactWheelEvent } from 'react'
import {
  addMonths,
  eachDayOfInterval,
  endOfMonth,
  endOfWeek,
  format,
  isSameDay,
  isSameMonth,
  parseISO,
  startOfMonth,
  startOfWeek,
  subMonths,
} from 'date-fns'
import { zhCN } from 'date-fns/locale'
import {
  ArrowRight,
  BarChart3,
  Bell,
  BookOpen,
  BrainCircuit,
  CalendarDays,
  Check,
  CheckCircle2,
  CheckSquare2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Circle,
  Clock3,
  Download,
  ExternalLink,
  FileText,
  FolderOpen,
  Lightbulb,
  Link2,
  ListChecks,
  Menu,
  MoreHorizontal,
  Network,
  NotebookPen,
  PenLine,
  Play,
  Plus,
  RefreshCw,
  Save,
  Search,
  Sparkles,
  SquareCheckBig,
  Tag,
  Target,
  TrendingUp,
  X,
} from 'lucide-react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  calendarItems as initialCalendarItems,
  dailyCompletion,
  eventMaterials,
  heatmapValues,
  monthlyHistory,
  notes as initialNotes,
  plans,
  sourceMaterials,
  type CalendarItem,
  type Note,
  type Plan,
  type PlanItem,
  type TodoItem,
  initialTodos,
} from './data'
import './App.css'
import { plannerApi, type AiPlanChange, type AiReviewMessage, type PlannerStats } from './api'

const initialStats: PlannerStats = {
  daily: dailyCompletion,
  heatmap: heatmapValues.map((cell) => ({ ...cell, id: String(cell.id) })),
  monthly: monthlyHistory,
  metrics: { completion: 81, completed: 82, planned: 101, focusHours: 46.5, streak: 12 },
}

type View = 'calendar' | 'plans' | 'todos' | 'notes' | 'stats' | 'review' | 'schedule'
type NavView = Exclude<View, 'schedule'>
type GlobalTargetType = 'plan' | 'todo' | 'note' | 'schedule'

interface GlobalSearchItem {
  id: string
  type: GlobalTargetType
  title: string
  meta: string
}

interface NotificationEntry extends GlobalSearchItem {
  time?: string
}

const weekLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

const navItems: Array<{ id: NavView; label: string; icon: typeof CalendarDays }> = [
  { id: 'calendar', label: '日历', icon: CalendarDays },
  { id: 'plans', label: '计划', icon: ListChecks },
  { id: 'todos', label: '待办', icon: CheckSquare2 },
  { id: 'notes', label: '笔记', icon: NotebookPen },
  { id: 'stats', label: '计划统计', icon: BarChart3 },
  { id: 'review', label: 'AI 复盘', icon: BrainCircuit },
]

function ProgressRing({ value, color, size = 42 }: { value: number; color: string; size?: number }) {
  const style = {
    '--progress': value * 3.6 + 'deg',
    '--ring-color': color,
    width: size,
    height: size,
  } as CSSProperties

  return (
    <div className="progress-ring" style={style} aria-label={'进度 ' + value + '%'}>
      <span>{value}</span>
    </div>
  )
}

function getCalendarProgress(item: CalendarItem, plansData: Plan[]) {
  if (item.kind === 'todo') return item.status === 'done' ? 100 : 0
  if (item.status === 'done') return 100
  return item.progress ?? plansData.find((plan) => plan.id === item.planId)?.progress ?? 0
}

function Sidebar({
  activeView,
  plansData,
  onViewChange,
  onPlanOpen,
  onCreatePlan,
  mobileOpen,
  onMobileClose,
}: {
  activeView: View
  plansData: Plan[]
  onViewChange: (view: View) => void
  onPlanOpen: (planId: string) => void
  onCreatePlan: () => void
  mobileOpen: boolean
  onMobileClose: () => void
}) {
  const [expandedPlans, setExpandedPlans] = useState<string[]>(['product', 'travel'])

  const togglePlan = (planId: string) => {
    setExpandedPlans((current) =>
      current.includes(planId) ? current.filter((id) => id !== planId) : [...current, planId],
    )
  }

  return (
    <>
      <button
        className={'sidebar-scrim ' + (mobileOpen ? 'visible' : '')}
        type="button"
        aria-label="关闭导航"
        onClick={onMobileClose}
      />
      <aside className={'sidebar ' + (mobileOpen ? 'mobile-open' : '')}>
        <div className="brand-row">
          <div className="brand-mark"><Target size={19} strokeWidth={2.4} /></div>
          <div>
            <strong>长路</strong>
            <span>计划工作台</span>
          </div>
          <button className="icon-button mobile-close" type="button" onClick={onMobileClose} title="关闭导航">
            <X size={18} />
          </button>
        </div>

        <nav className="main-nav" aria-label="主要功能">
          {navItems.map((item) => {
            const Icon = item.icon
            return (
              <button
                key={item.id}
                type="button"
                className={activeView === item.id ? 'active' : ''}
                onClick={() => {
                  onViewChange(item.id)
                  onMobileClose()
                }}
              >
                <Icon size={18} strokeWidth={2} />
                <span>{item.label}</span>
              </button>
            )
          })}
        </nav>

        <div className="sidebar-divider" />

        <div className="plan-section-header">
          <div>
            <span className="eyebrow">长期计划</span>
          <strong>{plansData.length} 项正在推进</strong>
        </div>
          <button className="icon-button" type="button" title="添加长期计划" onClick={onCreatePlan}>
            <Plus size={18} />
          </button>
        </div>

        <div className="sidebar-plans">
          {plansData.map((plan) => {
            const expanded = expandedPlans.includes(plan.id)
            return (
              <section className="sidebar-plan" key={plan.id}>
                <button className="plan-summary" type="button" onClick={() => togglePlan(plan.id)}>
                  <ProgressRing value={plan.progress} color={plan.color} />
                  <span className="plan-summary-copy">
                    <strong>{plan.title}</strong>
                    <small>{plan.subtitle}</small>
                  </span>
                  <ChevronDown className={expanded ? 'rotated' : ''} size={17} />
                </button>
                {expanded && (
                  <div className="plan-subitems">
                    {plan.items.map((item) => (
                      <button type="button" key={item.id} onClick={() => onPlanOpen(plan.id)}>
                        <span className="subitem-title">{item.title}</span>
                        <span className="subitem-meta">
                          <span>{item.progress}%</span>
                          <span className="mini-track">
                            <i style={{ width: item.progress + '%', backgroundColor: plan.color }} />
                          </span>
                        </span>
                      </button>
                    ))}
                    <button className="open-plan" type="button" onClick={() => onPlanOpen(plan.id)}>
                      查看完整计划 <ArrowRight size={14} />
                    </button>
                  </div>
                )}
              </section>
            )
          })}
        </div>

        <div className="sidebar-footer">
          <div className="avatar">陈</div>
          <div><strong>虫语折.</strong><span>本周完成率 81%</span></div>
          <button className="icon-button" type="button" title="更多设置"><MoreHorizontal size={18} /></button>
        </div>
      </aside>
    </>
  )
}

function AppHeader({
  title,
  subtitle,
  onMenu,
  onCreateChoice,
  searchItems,
  notifications,
  onOpenItem,
}: {
  title: string
  subtitle: string
  onMenu: () => void
  onCreateChoice: (kind: 'todo' | 'schedule') => void
  searchItems: GlobalSearchItem[]
  notifications: NotificationEntry[]
  onOpenItem: (item: GlobalSearchItem) => void
}) {
  const [createOpen, setCreateOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [notificationOpen, setNotificationOpen] = useState(false)
  const [query, setQuery] = useState('')

  const filteredSearchItems = searchItems
    .filter((item) => !query.trim() || (item.title + item.meta).toLowerCase().includes(query.trim().toLowerCase()))
    .slice(0, 8)

  const typeLabel: Record<GlobalTargetType, string> = {
    plan: '计划',
    todo: '待办',
    note: '笔记',
    schedule: '日程',
  }

  const openItem = (item: GlobalSearchItem) => {
    onOpenItem(item)
    setSearchOpen(false)
    setNotificationOpen(false)
  }

  const selectCreate = (kind: 'todo' | 'schedule') => {
    onCreateChoice(kind)
    setCreateOpen(false)
  }

  return (
    <header className="app-header">
      <button className="icon-button menu-button" type="button" onClick={onMenu} title="打开导航">
        <Menu size={20} />
      </button>
      <div className="page-title">
        <h1>{title}</h1>
        <span>{subtitle}</span>
      </div>
      <div className="header-actions">
        <div className="header-popover-wrap">
          <button
            className="icon-button"
            type="button"
            title="搜索"
            onClick={() => { setSearchOpen((value) => !value); setNotificationOpen(false); setCreateOpen(false) }}
            aria-expanded={searchOpen}
          >
            <Search size={19} />
          </button>
          {searchOpen && (
            <section className="header-panel search-panel">
              <div className="panel-search-box"><Search size={15} /><input autoFocus value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索计划、待办、笔记、日程" /></div>
              <div className="panel-list">
                {filteredSearchItems.map((item) => (
                  <button type="button" key={item.type + item.id} onClick={() => openItem(item)}>
                    <span className={'panel-kind kind-' + item.type}>{typeLabel[item.type]}</span>
                    <strong>{item.title}</strong>
                    <small>{item.meta}</small>
                  </button>
                ))}
                {filteredSearchItems.length === 0 && <div className="panel-empty">没有找到相关内容</div>}
              </div>
            </section>
          )}
        </div>
        <div className="header-popover-wrap">
          <button
            className="icon-button notification-button"
            type="button"
            title="通知"
            onClick={() => { setNotificationOpen((value) => !value); setSearchOpen(false); setCreateOpen(false) }}
            aria-expanded={notificationOpen}
          >
            <Bell size={19} />
            {notifications.length > 0 && <i />}
          </button>
          {notificationOpen && (
            <section className="header-panel notification-panel">
              <div className="panel-heading"><strong>提醒中心</strong><span>{notifications.length} 条待处理</span></div>
              <div className="panel-list">
                {notifications.map((item) => (
                  <button type="button" key={item.type + item.id} onClick={() => openItem(item)}>
                    <span className={'panel-kind kind-' + item.type}>{typeLabel[item.type]}</span>
                    <strong>{item.title}</strong>
                    <small>{item.time ? item.time + ' · ' : ''}{item.meta}</small>
                  </button>
                ))}
                {notifications.length === 0 && <div className="panel-empty">暂时没有新的提醒</div>}
              </div>
            </section>
          )}
        </div>
        <div className="create-menu-wrap">
          <button className="primary-button" type="button" onClick={() => { setCreateOpen((value) => !value); setSearchOpen(false); setNotificationOpen(false) }} aria-haspopup="menu" aria-expanded={createOpen}>
            <Plus size={17} /> 新建
          </button>
          {createOpen && (
            <div className="create-menu" role="menu">
              <button type="button" role="menuitem" onClick={() => selectCreate('todo')}><CheckSquare2 size={16} /> 新建待办<span>一次性任务</span></button>
              <button type="button" role="menuitem" onClick={() => selectCreate('schedule')}><CalendarDays size={16} /> 新建日程<span>放入日历</span></button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}

function DayDrawer({
  date,
  items,
  plansData,
  onClose,
  onToggleItem,
  onOpenItem,
  onAdd,
}: {
  date: Date
  items: CalendarItem[]
  plansData: Plan[]
  onClose: () => void
  onToggleItem: (id: string) => void
  onOpenItem: (item: CalendarItem) => void
  onAdd: () => void
}) {
  const dateLabel = format(date, 'M月d日 EEEE', { locale: zhCN })
  const completed = items.filter((item) => item.status === 'done').length
  const totalMinutes = items.reduce((total, item) => total + item.duration, 0)

  return (
    <aside className="day-drawer">
      <div className="drawer-header">
        <div>
          <span className="eyebrow">当日安排</span>
          <h2>{dateLabel}</h2>
        </div>
        <button className="icon-button" type="button" onClick={onClose} title="关闭">
          <X size={19} />
        </button>
      </div>
      <div className="day-summary">
        <div><strong>{items.length}</strong><span>项安排</span></div>
        <div><strong>{completed}</strong><span>已完成</span></div>
        <div><strong>{Math.round(totalMinutes / 30) / 2}</strong><span>预计小时</span></div>
      </div>
      <div className="day-agenda">
        {items.length === 0 ? (
          <div className="empty-day">
            <CalendarDays size={28} />
            <strong>今天还没有安排</strong>
            <span>给长期计划留出一个具体时间。</span>
          </div>
        ) : items.map((item) => (
          <article className={'agenda-item ' + (item.status === 'done' ? 'done' : '')} key={item.id}>
            <button type="button" onClick={() => onToggleItem(item.id)} aria-label="切换完成状态">
              {item.status === 'done' ? <CheckCircle2 size={20} /> : <Circle size={20} />}
            </button>
            <button className="agenda-open" type="button" onClick={() => onOpenItem(item)}>
              <span className="agenda-title"><strong>{item.title}</strong><b>{getCalendarProgress(item, plansData)}%</b></span>
              <span className="agenda-progress"><i style={{ width: getCalendarProgress(item, plansData) + '%', backgroundColor: item.color }} /></span>
              <span className="agenda-meta"><Clock3 size={13} /> {item.time} · {item.duration} 分钟 {item.kind === 'todo' ? '· 一次性待办' : ''}</span>
            </button>
            <i style={{ backgroundColor: item.color }} />
          </article>
        ))}
      </div>
      <button className="drawer-add" type="button" onClick={onAdd}>
        <Plus size={17} /> 添加当天安排
      </button>
    </aside>
  )
}

function CalendarPage({
  items,
  plansData,
  onToggleItem,
  onOpenItem,
  onAdd,
}: {
  items: CalendarItem[]
  plansData: Plan[]
  onToggleItem: (id: string) => void
  onOpenItem: (item: CalendarItem) => void
  onAdd: (date: Date) => void
}) {
  const [month, setMonth] = useState(new Date(2026, 7, 1))
  const [selectedDate, setSelectedDate] = useState<Date | null>(new Date(2026, 7, 2))

  const days = useMemo(() => {
    const start = startOfWeek(startOfMonth(month), { weekStartsOn: 1 })
    const end = endOfWeek(endOfMonth(month), { weekStartsOn: 1 })
    return eachDayOfInterval({ start, end })
  }, [month])

  const itemsForDate = (date: Date) =>
    items.filter((item) => isSameDay(parseISO(item.date), date))

  const selectedItems = selectedDate ? itemsForDate(selectedDate) : []
  return (
    <div className="calendar-page">
      <div className="calendar-toolbar">
        <div className="month-controls">
          <button className="icon-button" type="button" onClick={() => setMonth(subMonths(month, 1))} title="上个月">
            <ChevronLeft size={19} />
          </button>
          <strong>{format(month, 'yyyy 年 M 月', { locale: zhCN })}</strong>
          <button className="icon-button" type="button" onClick={() => setMonth(addMonths(month, 1))} title="下个月">
            <ChevronRight size={19} />
          </button>
          <button className="text-button" type="button" onClick={() => setMonth(new Date(2026, 7, 1))}>今天</button>
        </div>
        <div className="calendar-legend" aria-label="计划颜色">
          {plansData.slice(0, 4).map((plan) => (
            <span key={plan.id}><i style={{ backgroundColor: plan.color }} />{plan.title.slice(0, 4)}</span>
          ))}
        </div>
      </div>

      <div className="calendar-shell">
        <div className="weekday-row">
          {weekLabels.map((label) => <span key={label}>{label}</span>)}
        </div>
        <div className="month-grid">
          {days.map((day) => {
            const dayItems = itemsForDate(day)
            const selected = selectedDate && isSameDay(day, selectedDate)
            return (
              <button
                type="button"
                className={[
                  'day-cell',
                  !isSameMonth(day, month) ? 'outside-month' : '',
                  selected ? 'selected' : '',
                  isSameDay(day, new Date(2026, 7, 2)) ? 'today' : '',
                ].filter(Boolean).join(' ')}
                key={day.toISOString()}
                onClick={() => setSelectedDate(day)}
              >
                <span className="day-number">{format(day, 'd')}</span>
                <span className="day-events">
                  {dayItems.slice(0, 3).map((item) => (
                    <span
                      className={'calendar-event ' + (item.status === 'done' ? 'done' : '')}
                      style={{ borderLeftColor: item.color }}
                      key={item.id}
                    >
                      <span className="calendar-event-top"><i style={{ backgroundColor: item.color }} /><b>{item.time}</b><em>{item.title}</em></span>
                      <span className="calendar-event-progress"><i style={{ width: getCalendarProgress(item, plansData) + '%', backgroundColor: item.color }} /></span>
                    </span>
                  ))}
                  {dayItems.length > 3 && <small>还有 {dayItems.length - 3} 项</small>}
                </span>
              </button>
            )
          })}
        </div>
        {selectedDate && (
          <DayDrawer
            date={selectedDate}
            items={selectedItems}
            plansData={plansData}
            onClose={() => setSelectedDate(null)}
            onToggleItem={onToggleItem}
            onOpenItem={onOpenItem}
            onAdd={() => onAdd(selectedDate)}
          />
        )}
      </div>
    </div>
  )
}

function PlansPage({
  activePlanId,
  plansData,
  calendarItems,
  onPlanChange,
  onUpdatePlan,
  onDeletePlan,
  onCreateStage,
  onUpdateStage,
  onDeleteStage,
  onAddSchedule,
}: {
  activePlanId: string
  plansData: Plan[]
  calendarItems: CalendarItem[]
  onPlanChange: (id: string) => void
  onUpdatePlan: (plan: Plan) => void
  onDeletePlan: (id: string) => void
  onCreateStage: (planId: string, item: PlanItem) => void
  onUpdateStage: (planId: string, item: PlanItem) => void
  onDeleteStage: (planId: string, itemId: string) => void
  onAddSchedule: (planId: string) => void
}) {
  const plan = plansData.find((item) => item.id === activePlanId) ?? plansData[0]
  const [editingPlan, setEditingPlan] = useState(false)
  const [editingStageId, setEditingStageId] = useState<string | null>(null)
  const [stageDraft, setStageDraft] = useState({ title: '', dueLabel: '', progress: 0 })

  if (!plan) return <div className="content-page simple-empty">还没有长期计划，先创建一个计划吧。</div>

  const nextItems = calendarItems.filter((item) => item.planId === plan.id).slice(0, 5)

  const updatePlanField = (field: 'title' | 'subtitle' | 'dueDate' | 'color', value: string) => {
    onUpdatePlan({ ...plan, [field]: value })
  }

  const startStageEdit = (item: PlanItem) => {
    setEditingStageId(item.id)
    setStageDraft({ title: item.title, dueLabel: item.dueLabel, progress: item.progress })
  }

  const saveStage = () => {
    if (!stageDraft.title.trim()) return
    const item: PlanItem = {
      id: !editingStageId || editingStageId === 'new' ? 'stage-' + Date.now() : editingStageId,
      title: stageDraft.title.trim(),
      dueLabel: stageDraft.dueLabel.trim() || '待安排',
      progress: Math.max(0, Math.min(100, Number(stageDraft.progress) || 0)),
    }
    if (editingStageId && editingStageId !== 'new') onUpdateStage(plan.id, item)
    else onCreateStage(plan.id, item)
    setEditingStageId(null)
    setStageDraft({ title: '', dueLabel: '', progress: 0 })
  }

  return (
    <div className="plans-page content-page">
      <div className="plans-switcher" role="tablist" aria-label="选择长期计划">
        {plansData.map((item) => (
          <button
            type="button"
            role="tab"
            aria-selected={item.id === plan.id}
            className={item.id === plan.id ? 'active' : ''}
            key={item.id}
            onClick={() => onPlanChange(item.id)}
          >
            <i style={{ backgroundColor: item.color }} />
            <span>{item.title}</span>
            <b>{item.progress}%</b>
          </button>
        ))}
      </div>

      <section className="plan-overview">
        <div className="plan-overview-main">
          <ProgressRing value={plan.progress} color={plan.color} size={76} />
          <div>
            <span className="eyebrow">长期计划</span>
            <h2>{plan.title}</h2>
            <p>{plan.completedTasks} 项已完成，共 {plan.totalTasks} 项 · 目标日期 {plan.dueDate}</p>
          </div>
        </div>
        <div className="overview-actions">
          <button className="secondary-button" type="button"><Sparkles size={17} /> AI 调整</button>
          <button className="secondary-button" type="button" onClick={() => setEditingPlan((value) => !value)}><PenLine size={16} /> 编辑计划</button>
          <button className="primary-button" type="button" onClick={() => onAddSchedule(plan.id)}><Plus size={17} /> 添加安排</button>
        </div>
      </section>

      {editingPlan && (
        <section className="inline-editor plan-inline-editor">
          <div className="inline-editor-fields">
            <label>计划名称<input value={plan.title} onChange={(event) => updatePlanField('title', event.target.value)} /></label>
            <label>副标题<input value={plan.subtitle} onChange={(event) => updatePlanField('subtitle', event.target.value)} /></label>
            <label>目标日期<input type="date" value={plan.dueDate} onChange={(event) => updatePlanField('dueDate', event.target.value)} /></label>
            <label>颜色<input type="color" value={plan.color} onChange={(event) => updatePlanField('color', event.target.value)} /></label>
          </div>
          <button className="text-button danger-text" type="button" onClick={() => onDeletePlan(plan.id)}>删除这个计划</button>
        </section>
      )}

      <div className="plan-detail-grid">
        <section className="plan-stages">
          <div className="section-heading">
            <div><span className="eyebrow">阶段</span><h3>推进路径</h3></div>
            <button className="secondary-button" type="button" onClick={() => { setEditingStageId('new'); setStageDraft({ title: '', dueLabel: '', progress: 0 }) }}><Plus size={15} /> 添加阶段</button>
          </div>
          {plan.items.map((item, index) => (
            <article className="stage-row" key={item.id}>
              <span className="stage-index">{String(index + 1).padStart(2, '0')}</span>
              {editingStageId === item.id ? (
                <div className="stage-edit-fields">
                  <input value={stageDraft.title} onChange={(event) => setStageDraft({ ...stageDraft, title: event.target.value })} />
                  <input value={stageDraft.dueLabel} onChange={(event) => setStageDraft({ ...stageDraft, dueLabel: event.target.value })} placeholder="截止时间" />
                  <input type="number" min="0" max="100" value={stageDraft.progress} onChange={(event) => setStageDraft({ ...stageDraft, progress: Number(event.target.value) })} />
                </div>
              ) : (
                <div className="stage-copy">
                  <strong>{item.title}</strong>
                  <span>{item.dueLabel}</span>
                  <div className="stage-track"><i style={{ width: item.progress + '%', backgroundColor: plan.color }} /></div>
                </div>
              )}
              {editingStageId === item.id ? (
                <span className="row-actions"><button className="icon-button" type="button" onClick={saveStage} title="保存"><Check size={15} /></button><button className="icon-button" type="button" onClick={() => setEditingStageId(null)} title="取消"><X size={15} /></button></span>
              ) : (
                <span className="row-actions"><b>{item.progress}%</b><button className="icon-button" type="button" onClick={() => startStageEdit(item)} title="编辑阶段"><PenLine size={14} /></button><button className="icon-button danger-icon" type="button" onClick={() => onDeleteStage(plan.id, item.id)} title="删除阶段"><X size={14} /></button></span>
              )}
            </article>
          ))}
          {editingStageId === 'new' && (
            <div className="stage-new-form">
              <input value={stageDraft.title} onChange={(event) => setStageDraft({ ...stageDraft, title: event.target.value })} placeholder="阶段名称" />
              <input value={stageDraft.dueLabel} onChange={(event) => setStageDraft({ ...stageDraft, dueLabel: event.target.value })} placeholder="截止时间" />
              <input type="number" min="0" max="100" value={stageDraft.progress} onChange={(event) => setStageDraft({ ...stageDraft, progress: Number(event.target.value) })} />
              <button className="primary-button" type="button" onClick={saveStage}><Check size={15} /> 保存</button>
            </div>
          )}
        </section>

        <section className="next-actions">
          <div className="section-heading">
            <div><span className="eyebrow">接下来</span><h3>近期任务</h3></div>
            <button className="text-button" type="button">查看全部</button>
          </div>
          {nextItems.map((item) => (
            <article className="next-action-row" key={item.id}>
              <Circle size={19} />
              <div><strong>{item.title}</strong><span>{item.date} · {item.time}</span></div>
              <span>{item.duration}m</span>
            </article>
          ))}
          {nextItems.length === 0 && <div className="simple-empty">该计划暂时没有近期安排。</div>}
        </section>
      </div>
    </div>
  )
}

function TodosPage({
  todoItems,
  onCreate,
  onUpdate,
  onDelete,
  onToggle,
}: {
  todoItems: TodoItem[]
  onCreate: (item: TodoItem) => void
  onUpdate: (item: TodoItem) => void
  onDelete: (id: string) => void
  onToggle: (id: string) => void
}) {
  const today = format(new Date(), 'yyyy-MM-dd')
  const [filter, setFilter] = useState<'全部' | '今天' | '已完成'>('全部')
  const [newTitle, setNewTitle] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [draft, setDraft] = useState<TodoItem | null>(null)

  const visibleItems = todoItems.filter((item) => {
    if (filter === '今天') return item.date === today && !item.done
    if (filter === '已完成') return item.done
    return true
  })

  const addTodo = () => {
    const title = newTitle.trim()
    if (!title) return
    onCreate({ id: 'td-' + Date.now(), title, date: '', time: '', priority: '中', done: false, reminder: '无提醒' })
    setNewTitle('')
  }

  const displayDate = (date: string) => date === today ? '今天' : format(parseISO(date), 'M 月 d 日')

  const startEdit = (item: TodoItem) => { setEditingId(item.id); setDraft({ ...item }) }

  const saveEdit = () => {
    if (draft?.title.trim()) onUpdate({ ...draft, title: draft.title.trim() })
    setEditingId(null)
    setDraft(null)
  }

  return (
    <div className="todos-page content-page">
      <section className="todo-quick-add">
        <SquareCheckBig size={20} />
        <input
          value={newTitle}
          onChange={(event) => setNewTitle(event.target.value)}
          onKeyDown={(event) => event.key === 'Enter' && addTodo()}
          placeholder="添加一件只需要完成一次的事"
          aria-label="新待办标题"
        />
        <button className="primary-button" type="button" onClick={addTodo}><Plus size={16} /> 添加待办</button>
      </section>

      <div className="todo-toolbar">
        <div className="segmented-control">
          {(['全部', '今天', '已完成'] as const).map((item) => (
            <button type="button" className={filter === item ? 'active' : ''} onClick={() => setFilter(item)} key={item}>{item}</button>
          ))}
        </div>
        <span>待完成 {todoItems.filter((item) => !item.done).length} 项</span>
      </div>

      <section className="todo-list">
        <div className="todo-list-head">
          <span>待办事项</span><span>日期与时间</span><span>提醒</span><span>优先级</span><span />
        </div>
        {visibleItems.map((item) => (
            <article className={'todo-row ' + (item.done ? 'done' : '')} key={item.id}>
              <button
                type="button"
                className="todo-check"
                onClick={() => onToggle(item.id)}
                aria-label="切换待办完成状态"
              >
                {item.done ? <CheckCircle2 size={20} /> : <Circle size={20} />}
              </button>
              {editingId === item.id && draft ? (
                <div className="todo-edit-fields">
                  <input value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} />
                  <input type="date" value={draft.date} onChange={(event) => setDraft({ ...draft, date: event.target.value })} />
                  <input type="time" value={draft.time === '未安排' ? '' : draft.time} onChange={(event) => setDraft({ ...draft, time: event.target.value || '未安排' })} />
                  <select value={draft.priority} onChange={(event) => setDraft({ ...draft, priority: event.target.value as TodoItem['priority'] })}><option>高</option><option>中</option><option>低</option></select>
                  <select value={draft.reminder} onChange={(event) => setDraft({ ...draft, reminder: event.target.value })}><option>无提醒</option><option>到点提醒</option><option>提前 30 分钟</option><option>提前 2 小时</option><option>提前 1 天</option></select>
                </div>
              ) : (
                <>
                  <strong>{item.title}</strong>
                  <span><CalendarDays size={14} /> {item.date ? `${displayDate(item.date)} · ${item.time}` : '未安排'}</span>
                  <span><Bell size={14} /> {item.reminder}</span>
                  <b className={'priority priority-' + item.priority}>{item.priority}</b>
                </>
              )}
              {editingId === item.id ? (
                <span className="row-actions"><button className="icon-button" type="button" onClick={saveEdit} title="保存"><Check size={15} /></button><button className="icon-button" type="button" onClick={() => { setEditingId(null); setDraft(null) }} title="取消"><X size={15} /></button></span>
              ) : (
                <span className="row-actions"><button className="icon-button" type="button" title="编辑待办" onClick={() => startEdit(item)}><PenLine size={15} /></button><button className="icon-button danger-icon" type="button" title="删除待办" onClick={() => onDelete(item.id)}><X size={15} /></button></span>
              )}
            </article>
        ))}
      </section>
    </div>
  )
}

const graphPositions: Record<string, { x: number; y: number }> = {
  n1: { x: 330, y: 120 },
  n2: { x: 485, y: 190 },
  n3: { x: 270, y: 285 },
  n4: { x: 575, y: 90 },
  n5: { x: 420, y: 360 },
  n6: { x: 135, y: 225 },
  n7: { x: 650, y: 285 },
  n8: { x: 175, y: 390 },
}

function NoteGraph({ noteItems, activeId, onSelect }: { noteItems: Note[]; activeId: string; onSelect: (id: string) => void }) {
  const initialPositions = Object.fromEntries(noteItems.map((note, index) => {
    const fallback = {
      x: 380 + Math.cos((index / Math.max(noteItems.length, 1)) * Math.PI * 2) * 260,
      y: 235 + Math.sin((index / Math.max(noteItems.length, 1)) * Math.PI * 2) * 170,
    }
    return [note.id, graphPositions[note.id] ?? fallback]
  })) as Record<string, { x: number; y: number }>
  const [positions, setPositions] = useState<Record<string, { x: number; y: number }>>(initialPositions)
  const [viewport, setViewport] = useState({ x: 0, y: 0, scale: 1 })
  const svgRef = useRef<SVGSVGElement | null>(null)
  const interactionRef = useRef<{ kind: 'node' | 'pan'; noteId?: string; startX: number; startY: number; originX: number; originY: number } | null>(null)
  const suppressClickRef = useRef(false)

  useEffect(() => {
    setPositions((current) => {
      const next: Record<string, { x: number; y: number }> = {}
      noteItems.forEach((note, index) => {
        const fallback = {
          x: 380 + Math.cos((index / Math.max(noteItems.length, 1)) * Math.PI * 2) * 260,
          y: 235 + Math.sin((index / Math.max(noteItems.length, 1)) * Math.PI * 2) * 170,
        }
        next[note.id] = current[note.id] ?? graphPositions[note.id] ?? fallback
      })
      return next
    })
  }, [noteItems])

  const handlePointerDown = (clientX: number, clientY: number, noteId?: string) => {
    suppressClickRef.current = false
    const point = noteId ? positions[noteId] : viewport
    interactionRef.current = {
      kind: noteId ? 'node' : 'pan',
      noteId,
      startX: clientX,
      startY: clientY,
      originX: point.x,
      originY: point.y,
    }
  }

  const handlePointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    const interaction = interactionRef.current
    if (!interaction) return
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect) return
    const deltaX = event.clientX - interaction.startX
    const deltaY = event.clientY - interaction.startY
    if (Math.abs(deltaX) + Math.abs(deltaY) > 3) suppressClickRef.current = true
    if (interaction.kind === 'node' && interaction.noteId) {
      setPositions((current) => ({
        ...current,
        [interaction.noteId as string]: {
          x: interaction.originX + deltaX / rect.width * 760 / viewport.scale,
          y: interaction.originY + deltaY / rect.height * 470 / viewport.scale,
        },
      }))
    } else {
      setViewport((current) => ({
        ...current,
        x: interaction.originX + deltaX / rect.width * 760,
        y: interaction.originY + deltaY / rect.height * 470,
      }))
    }
  }

  const handlePointerUp = () => {
    const interaction = interactionRef.current
    if (interaction?.kind === 'node' && interaction.noteId && !suppressClickRef.current) {
      onSelect(interaction.noteId)
    }
    interactionRef.current = null
    window.setTimeout(() => { suppressClickRef.current = false }, 0)
  }

  const handleWheel = (event: ReactWheelEvent<SVGSVGElement>) => {
    event.preventDefault()
    const nextScale = Math.max(0.55, Math.min(2.1, viewport.scale - event.deltaY * 0.001))
    setViewport((current) => ({ ...current, scale: nextScale }))
  }

  const resetViewport = () => setViewport({ x: 0, y: 0, scale: 1 })
  const zoom = (amount: number) => setViewport((current) => ({ ...current, scale: Math.max(0.55, Math.min(2.1, current.scale + amount)) }))
  const relations = noteItems.flatMap((note) =>
    note.relatedIds
      .filter((relatedId) => note.id < relatedId && positions[relatedId])
      .map((relatedId) => ({ from: note.id, to: relatedId })),
  )

  return (
    <div className="note-graph">
      <div className="graph-tools" aria-label="图谱工具">
        <button type="button" onClick={() => zoom(-0.1)} title="缩小">−</button>
        <button type="button" onClick={resetViewport} title="重置视图">{Math.round(viewport.scale * 100)}%</button>
        <button type="button" onClick={() => zoom(0.1)} title="放大">+</button>
      </div>
      <svg
        ref={svgRef}
        viewBox="0 0 760 470"
        role="img"
        aria-label="笔记关系图谱，可拖动节点和平移画布"
        onPointerDown={(event) => { event.currentTarget.setPointerCapture(event.pointerId); handlePointerDown(event.clientX, event.clientY) }}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        onWheel={handleWheel}
      >
        <g transform={`translate(${viewport.x} ${viewport.y}) scale(${viewport.scale})`}>
          <g className="graph-links">
          {relations.map((relation) => (
            <line
              key={relation.from + relation.to}
              x1={positions[relation.from].x}
              y1={positions[relation.from].y}
              x2={positions[relation.to].x}
              y2={positions[relation.to].y}
            />
          ))}
          </g>
          <g className="graph-nodes">
          {noteItems.map((note) => {
            const position = positions[note.id]
            return (
              <g
                key={note.id}
                className={activeId === note.id ? 'active' : ''}
                onPointerDown={(event) => { event.stopPropagation(); svgRef.current?.setPointerCapture(event.pointerId); handlePointerDown(event.clientX, event.clientY, note.id) }}
                onClick={(event) => event.preventDefault()}
                onKeyDown={(event) => event.key === 'Enter' && onSelect(note.id)}
                role="button"
                tabIndex={0}
              >
                <circle cx={position.x} cy={position.y} r={activeId === note.id ? 28 : 22} fill={note.color} />
                <text x={position.x} y={position.y + 42} textAnchor="middle">{note.title.slice(0, 10)}</text>
              </g>
            )
          })}
          </g>
        </g>
      </svg>
      <div className="graph-legend">
        <span><i style={{ backgroundColor: '#7c647d' }} />学习笔记</span>
        <span><i style={{ backgroundColor: '#d39a24' }} />计划方法</span>
        <span><i style={{ backgroundColor: '#b85f42' }} />产品与收藏</span>
        <span><i style={{ backgroundColor: '#72806a' }} />复盘记录</span>
      </div>
    </div>
  )
}

function NotesPage({
  noteItems,
  onChange,
  onCreate,
  onDelete,
  selectedNoteId,
}: {
  noteItems: Note[]
  onChange: (notes: Note[]) => void
  onCreate: (category: string) => Note
  onDelete: (id: string) => void
  selectedNoteId?: string
}) {
  const [activeCategory, setActiveCategory] = useState('全部笔记')
  const [activeId, setActiveId] = useState(initialNotes[0].id)
  const [mode, setMode] = useState<'列表' | '关系图谱'>('列表')
  const [query, setQuery] = useState('')
  const [newCategory, setNewCategory] = useState('')
  const [isCreatingCategory, setIsCreatingCategory] = useState(false)

  useEffect(() => {
    if (selectedNoteId) setActiveId(selectedNoteId)
  }, [selectedNoteId])

  const categories = ['全部笔记', ...Array.from(new Set(noteItems.map((note) => note.category)))]
  const visibleNotes = noteItems.filter((note) => {
    const categoryMatch = activeCategory === '全部笔记' || note.category === activeCategory
    const queryMatch = !query.trim() || (note.title + note.excerpt).toLowerCase().includes(query.toLowerCase())
    return categoryMatch && queryMatch
  })
  const activeNote = noteItems.find((note) => note.id === activeId) ?? noteItems[0]

  if (!activeNote) {
    return (
      <div className="content-page simple-empty">
        还没有笔记，先创建第一条笔记吧。
        <button className="primary-button" type="button" onClick={() => onCreate('学习笔记')}>
          <Plus size={16} /> 新建笔记
        </button>
      </div>
    )
  }

  const updateActiveNote = (changes: Partial<Note>) => {
    onChange(noteItems.map((note) => note.id === activeNote.id ? { ...note, ...changes } : note))
  }

  const createNote = () => {
    const next = onCreate(activeCategory === '全部笔记' ? '学习笔记' : activeCategory)
    setActiveId(next.id)
    setMode('列表')
  }

  const createCategory = () => {
    const category = newCategory.trim()
    if (!category) return
    const next: Note = {
      id: 'n-' + Date.now(),
      title: '未命名笔记',
      category,
      excerpt: '',
      updatedAt: '刚刚',
      color: '#72806a',
      relatedIds: [],
      source: '个人创建',
    }
    onChange([next, ...noteItems])
    setActiveCategory(category)
    setActiveId(next.id)
    setNewCategory('')
    setIsCreatingCategory(false)
  }

  return (
    <div className="notes-page">
      <aside className="note-categories">
        <div className="note-category-heading">
          <span className="eyebrow">知识库</span>
          <strong>{noteItems.length} 篇长期笔记</strong>
        </div>
        {categories.map((category) => (
          <button
            type="button"
            className={activeCategory === category ? 'active' : ''}
            onClick={() => setActiveCategory(category)}
            key={category}
          >
            <FolderOpen size={16} />
            <span>{category}</span>
            <b>{category === '全部笔记' ? noteItems.length : noteItems.filter((note) => note.category === category).length}</b>
          </button>
        ))}
        {isCreatingCategory ? (
          <div className="category-create"><input autoFocus value={newCategory} onChange={(event) => setNewCategory(event.target.value)} onKeyDown={(event) => event.key === 'Enter' && createCategory()} placeholder="分类名称" /><button className="icon-button" type="button" onClick={createCategory} title="保存分类"><Check size={15} /></button></div>
        ) : <button className="new-category" type="button" onClick={() => setIsCreatingCategory(true)}><Plus size={15} /> 新建分类</button>}
      </aside>

      <section className="note-workspace">
        <div className="notes-toolbar">
          <div className="note-search"><Search size={16} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索笔记" /></div>
          <div className="segmented-control">
            {(['列表', '关系图谱'] as const).map((item) => (
              <button type="button" className={mode === item ? 'active' : ''} onClick={() => setMode(item)} key={item}>
                {item === '列表' ? <FileText size={14} /> : <Network size={14} />} {item}
              </button>
            ))}
          </div>
          <button className="primary-button" type="button" onClick={createNote}><Plus size={16} /> 新建笔记</button>
        </div>

        {mode === '关系图谱' ? (
          <div className="graph-workspace">
            <div className="section-heading">
              <div><span className="eyebrow">知识关系</span><h3>笔记关系图谱</h3></div>
              <span className="section-note">拖动节点整理图谱，点击节点打开笔记</span>
            </div>
            <NoteGraph noteItems={noteItems} activeId={activeId} onSelect={(id) => { setActiveId(id); setMode('列表') }} />
          </div>
        ) : (
          <div className="notes-layout">
            <div className="note-list">
              {visibleNotes.map((note) => (
                <button type="button" className={activeId === note.id ? 'active' : ''} onClick={() => setActiveId(note.id)} key={note.id}>
                  <span className="note-list-top"><i style={{ backgroundColor: note.color }} />{note.category}<small>{note.updatedAt}</small></span>
                  <strong>{note.title}</strong>
                  <p>{note.excerpt || '开始记录你的想法……'}</p>
                  <span className="note-source"><Link2 size={12} />{note.source}</span>
                </button>
              ))}
            </div>
            <article className="note-editor">
              <div className="note-editor-meta">
                <label><Tag size={14} /><select value={activeNote.category} onChange={(event) => updateActiveNote({ category: event.target.value })}>{categories.slice(1).map((category) => <option key={category}>{category}</option>)}</select></label>
                <span>{activeNote.updatedAt}</span>
                <button className="secondary-button" type="button"><Save size={15} /> 已保存</button>
                <button className="icon-button danger-icon" type="button" onClick={() => { onDelete(activeNote.id); setActiveId(noteItems.find((note) => note.id !== activeNote.id)?.id ?? '') }} title="删除笔记"><X size={15} /></button>
              </div>
              <input className="note-title-input" value={activeNote.title} onChange={(event) => updateActiveNote({ title: event.target.value, updatedAt: '刚刚' })} />
              <textarea
                className="note-body-input"
                value={activeNote.excerpt}
                onChange={(event) => updateActiveNote({ excerpt: event.target.value, updatedAt: '刚刚' })}
                placeholder="记录长期有价值的知识、想法与连接……"
              />
              <div className="related-notes">
                <span className="eyebrow">关联笔记</span>
                <div>
                  {activeNote.relatedIds.map((id) => {
                    const related = noteItems.find((note) => note.id === id)
                    return related ? <button type="button" onClick={() => setActiveId(related.id)} key={id}><i style={{ backgroundColor: related.color }} />{related.title}</button> : null
                  })}
                  <select className="relation-select" value="" onChange={(event) => {
                    const relatedId = event.target.value
                    if (!relatedId) return
                    onChange(noteItems.map((note) => note.id === activeNote.id
                      ? { ...note, relatedIds: Array.from(new Set([...note.relatedIds, relatedId])) }
                      : note.id === relatedId
                        ? { ...note, relatedIds: Array.from(new Set([...note.relatedIds, activeNote.id])) }
                        : note))
                  }}>
                    <option value="">添加关联</option>
                    {noteItems.filter((note) => note.id !== activeNote.id && !activeNote.relatedIds.includes(note.id)).map((note) => <option value={note.id} key={note.id}>{note.title}</option>)}
                  </select>
                </div>
              </div>
            </article>
          </div>
        )}
      </section>
    </div>
  )
}

function ScheduleDetailPage({
  item,
  plansData,
  noteItems,
  onNotesChange,
  onToggle,
  onEdit,
  onDelete,
  onBack,
}: {
  item: CalendarItem
  plansData: Plan[]
  noteItems: Note[]
  onNotesChange: (notes: Note[]) => void
  onToggle: (id: string) => void
  onEdit: (item: CalendarItem) => void
  onDelete: (id: string) => void
  onBack: () => void
}) {
  const [personalNote, setPersonalNote] = useState('今天要重点理解极限的直观含义，再通过三道典型题检查自己是否真的掌握。')
  const [saved, setSaved] = useState(true)
  const [savedSources, setSavedSources] = useState<string[]>([])
  const [personalNoteId, setPersonalNoteId] = useState<string | null>(null)
  const plan = plansData.find((value) => value.id === item.planId)
  const materialIds = eventMaterials[item.id] ?? sourceMaterials.slice(0, 2).map((material) => material.id)
  const materials = sourceMaterials.filter((material) => materialIds.includes(material.id))

  const saveSource = (materialId: string) => {
    if (savedSources.includes(materialId)) return
    const material = sourceMaterials.find((value) => value.id === materialId)
    if (!material) return
    const next: Note = {
      id: 'source-note-' + Date.now(),
      title: material.title,
      category: '灵感收藏',
      excerpt: material.summary,
      updatedAt: '刚刚',
      color: material.color,
      relatedIds: [],
      source: material.source,
    }
    onNotesChange([next, ...noteItems])
    setSavedSources((current) => [...current, materialId])
  }

  const savePersonalNote = () => {
    const next: Note = {
      id: personalNoteId ?? 'study-note-' + Date.now(),
      title: item.title + ' · 学习记录',
      category: '学习笔记',
      excerpt: personalNote,
      updatedAt: '刚刚',
      color: item.color,
      relatedIds: [],
      source: '日程学习记录',
    }
    onNotesChange(personalNoteId
      ? noteItems.map((note) => note.id === personalNoteId ? next : note)
      : [next, ...noteItems])
    setPersonalNoteId(next.id)
    setSaved(true)
  }

  return (
    <div className="schedule-detail content-page">
      <button className="back-button" type="button" onClick={onBack}><ChevronLeft size={17} /> 返回日历</button>
      <section className="schedule-heading">
        <div className="schedule-title-mark" style={{ backgroundColor: item.color }}><BookOpen size={22} /></div>
        <div>
          <span className="eyebrow">当日安排 · 学习</span>
          <h2>{item.title}</h2>
          <p>{item.date} · {item.time} · {item.duration} 分钟 {plan ? '· ' + plan.title : ''}</p>
        </div>
        <div className="schedule-heading-actions">
          <button className="secondary-button" type="button" onClick={() => onToggle(item.id)}><CheckCircle2 size={16} /> {item.status === 'done' ? '已完成' : '标记完成'}</button>
          <button className="icon-button" type="button" onClick={() => onEdit(item)} title="编辑安排"><PenLine size={16} /></button>
          <button className="icon-button danger-icon" type="button" onClick={() => onDelete(item.id)} title="删除安排"><X size={16} /></button>
        </div>
      </section>

      <div className="schedule-detail-grid">
        <section className="knowledge-content">
          <div className="section-heading">
            <div><span className="eyebrow">自动收集</span><h3>学习资料与知识笔记</h3></div>
            <button className="secondary-button" type="button"><RefreshCw size={15} /> 重新获取</button>
          </div>

          <article className="knowledge-summary">
            <div className="summary-mark"><Sparkles size={18} /></div>
            <div>
              <strong>开始前先抓住三个关键点</strong>
              <ul>
                <li>极限关注的是逼近过程，不要求函数在该点真正取到极限值。</li>
                <li>连续需要函数值存在、极限存在，并且两者相等。</li>
                <li>做题时先判断结构，再选择等价无穷小、夹逼或洛必达法则。</li>
              </ul>
            </div>
          </article>

          <div className="material-list">
            {materials.map((material) => {
              const isSaved = savedSources.includes(material.id)
              return (
                <article className="material-row" key={material.id}>
                  {material.source === '哔哩哔哩' ? (
                    <div className="video-preview">
                      <Play size={22} fill="currentColor" />
                      <span>{material.meta.split(' · ')[0]}</span>
                    </div>
                  ) : (
                    <div className="source-preview" style={{ backgroundColor: material.color }}>
                      {material.source === '小红书' ? <NotebookPen size={24} /> : <ExternalLink size={24} />}
                    </div>
                  )}
                  <div className="material-copy">
                    <span className="material-source">{material.source}</span>
                    <strong>{material.title}</strong>
                    <p>{material.summary}</p>
                    <small>{material.meta}</small>
                  </div>
                  <div className="material-actions">
                    <a
                      className="icon-button"
                      href={material.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      title={`打开${material.source}来源`}
                      aria-label={`打开${material.source}来源：${material.title}`}
                    >
                      <ExternalLink size={16} />
                    </a>
                    <button
                      className={isSaved ? 'saved-source' : 'icon-button'}
                      type="button"
                      title="收藏为笔记"
                      onClick={() => saveSource(material.id)}
                    >
                      {isSaved ? <Check size={16} /> : <Plus size={16} />}
                    </button>
                  </div>
                </article>
              )
            })}
          </div>
        </section>

        <aside className="schedule-notes">
          <div className="section-heading">
            <div><span className="eyebrow">我的记录</span><h3>本次学习笔记</h3></div>
            <span className="save-state">{saved ? '已保存' : '未保存'}</span>
          </div>
          <textarea value={personalNote} onChange={(event) => { setPersonalNote(event.target.value); setSaved(false) }} />
          <button className="primary-button note-save-button" type="button" onClick={savePersonalNote}><Save size={16} /> 保存到长期笔记</button>

          <div className="schedule-related">
            <span className="eyebrow">相关长期笔记</span>
            {noteItems.filter((note) => note.category === '学习笔记').slice(0, 3).map((note) => (
              <article key={note.id}>
                <i style={{ backgroundColor: note.color }} />
                <div><strong>{note.title}</strong><span>{note.updatedAt}</span></div>
                <ChevronRight size={15} />
              </article>
            ))}
          </div>
        </aside>
      </div>
    </div>
  )
}

function Heatmap({ values }: { values: PlannerStats['heatmap'] }) {
  const [tooltip, setTooltip] = useState<{
    left: number
    top: number
    label: string
    completed: number
    pending: number
  } | null>(null)

  const showTooltip = (element: HTMLElement, cell: PlannerStats['heatmap'][number]) => {
    const rect = element.getBoundingClientRect()
    setTooltip({
      left: rect.left + rect.width / 2,
      top: rect.top - 8,
      label: /^\d{4}-\d{2}-\d{2}$/.test(cell.id) ? cell.id : cell.label,
      completed: cell.completed ?? cell.value,
      pending: cell.pending ?? Math.max(0, (cell.planned ?? cell.value) - (cell.completed ?? cell.value)),
    })
  }

  return (
    <div className="heatmap-wrap">
      <div className="heatmap-labels">
        <span>周一</span><span>周三</span><span>周五</span>
      </div>
      <div className="heatmap-grid">
        {values.map((cell) => (
          <span
            key={cell.id}
            className={'heat level-' + cell.value}
            tabIndex={0}
            onMouseEnter={(event) => showTooltip(event.currentTarget, cell)}
            onMouseLeave={() => setTooltip(null)}
            onFocus={(event) => showTooltip(event.currentTarget, cell)}
            onBlur={() => setTooltip(null)}
          />
        ))}
      </div>
      <div className="heatmap-scale">
        <span>少</span>
        {[0, 1, 2, 3, 4, 5].map((level) => <i className={'heat level-' + level} key={level} />)}
        <span>多</span>
      </div>
      {tooltip && (
        <div className="heatmap-tooltip" style={{ left: tooltip.left, top: tooltip.top }} role="tooltip">
          <strong>{tooltip.label}</strong>
          <span><i className="completed" />已完成 <b>{tooltip.completed} 项</b></span>
          <span><i className="pending" />未完成 <b>{tooltip.pending} 项</b></span>
        </div>
      )}
    </div>
  )
}

function StatsTooltip({ active, payload, label }: { active?: boolean; payload?: Array<{ value: number; name: string; color: string }>; label?: string }) {
  if (!active || !payload?.length) return null
  return (
    <div className="chart-tooltip">
      <strong>{label}</strong>
      {payload.map((entry) => <span key={entry.name}><i style={{ backgroundColor: entry.color }} />{entry.name}：{entry.value}</span>)}
    </div>
  )
}

function StatsPage({ stats }: { stats: PlannerStats }) {
  const [period, setPeriod] = useState('月')
  const completedTotal = stats.metrics.completed
  const pendingTotal = Math.max(0, stats.metrics.planned - completedTotal)
  const activeDays = stats.daily.filter((item) => item.planned > 0).length
  const heatmapActiveDays = stats.heatmap.filter((item) => item.value > 0).length
  const completionComposition = [
    { name: '已完成', value: completedTotal, color: '#d39a24' },
    { name: '待完成', value: pendingTotal || 1, color: '#eee5d7' },
  ]

  return (
    <div className="stats-page content-page">
      <div className="stats-export-row"><span>将当前计划、日程和待办整理成可下载的统计文件</span><div className="export-actions"><button className="secondary-button" type="button" onClick={() => void plannerApi.downloadExcel()}><Download size={16} /> 导出 Excel</button><button className="primary-button" type="button" onClick={() => void plannerApi.downloadPdf()}><FileText size={16} /> 导出 PDF</button></div></div>
      <div className="stats-toolbar">
        <div className="segmented-control">
          {['月', '季度', '年度'].map((item) => (
            <button type="button" className={period === item ? 'active' : ''} onClick={() => setPeriod(item)} key={item}>{item}</button>
          ))}
        </div>
        <button className="secondary-button" type="button"><RefreshCw size={16} /> 更新统计</button>
      </div>

      <section className="metric-strip">
        <div><span>本月完成率</span><strong>{stats.metrics.completion}%</strong><small className="positive"><TrendingUp size={13} /> 实时统计</small></div>
        <div><span>完成任务</span><strong>{stats.metrics.completed}</strong><small>计划 {stats.metrics.planned} 项</small></div>
        <div><span>专注时间</span><strong>{stats.metrics.focusHours}h</strong><small>已完成日程</small></div>
        <div><span>连续完成</span><strong>{stats.metrics.streak} 天</strong><small>截至今天</small></div>
      </section>

      <section className="stats-section heatmap-section">
        <div className="section-heading">
          <div><span className="eyebrow">完成热力</span><h3>近四个月执行记录</h3></div>
          <span className="section-note">共完成 {stats.heatmap.reduce((sum, item) => sum + item.value, 0)} 项</span>
        </div>
        <div className="heatmap-layout">
          <Heatmap values={stats.heatmap} />
          <aside className="execution-summary">
            <div className="execution-summary-heading"><span className="eyebrow">执行概览</span><strong>计划完成构成</strong></div>
            <div className="completion-donut">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={completionComposition} dataKey="value" nameKey="name" innerRadius="66%" outerRadius="88%" paddingAngle={3} stroke="none">
                    {completionComposition.map((entry) => <Cell key={entry.name} fill={entry.color} />)}
                  </Pie>
                </PieChart>
              </ResponsiveContainer>
              <div><strong>{stats.metrics.completion}%</strong><span>完成率</span></div>
            </div>
            <div className="execution-legend">
              {completionComposition.map((entry) => <span key={entry.name}><i style={{ backgroundColor: entry.color }} />{entry.name}<b>{entry.name === '已完成' ? completedTotal : pendingTotal}</b></span>)}
            </div>
            <div className="execution-facts"><span><b>{activeDays}</b> 个活跃日</span><span><b>{heatmapActiveDays}</b> 天有完成记录</span></div>
          </aside>
        </div>
      </section>

      <div className="charts-grid">
        <section className="stats-section chart-section">
          <div className="section-heading">
            <div><span className="eyebrow">每日完成</span><h3>8 月任务完成情况</h3></div>
            <div className="tiny-legend"><span><i className="planned" />计划</span><span><i className="completed" />完成</span></div>
          </div>
          <div className="chart-box">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={stats.daily} barGap={1}>
                <CartesianGrid vertical={false} stroke="#e8ddca" strokeDasharray="3 3" />
                <XAxis dataKey="day" tickLine={false} axisLine={false} interval={4} tick={{ fill: '#826f5d', fontSize: 11 }} />
                <YAxis tickLine={false} axisLine={false} width={24} tick={{ fill: '#826f5d', fontSize: 11 }} />
                <Tooltip content={<StatsTooltip />} cursor={{ fill: '#f2eadc' }} />
                <Bar dataKey="planned" name="计划" fill="#dac6a1" radius={[2, 2, 0, 0]} />
                <Bar dataKey="completed" name="完成" fill="#d39a24" radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </section>

        <section className="stats-section chart-section">
          <div className="section-heading">
            <div><span className="eyebrow">历史趋势</span><h3>近六个月完成率</h3></div>
            <span className="section-note">稳定提升</span>
          </div>
          <div className="chart-box">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={stats.monthly}>
                <CartesianGrid vertical={false} stroke="#e8ddca" strokeDasharray="3 3" />
                <XAxis dataKey="month" tickLine={false} axisLine={false} tick={{ fill: '#826f5d', fontSize: 11 }} />
                <YAxis domain={[40, 100]} tickLine={false} axisLine={false} width={30} tick={{ fill: '#826f5d', fontSize: 11 }} />
                <Tooltip content={<StatsTooltip />} />
                <Line type="monotone" dataKey="completion" name="完成率" stroke="#73806a" strokeWidth={3} dot={{ r: 4, fill: '#73806a', strokeWidth: 0 }} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </section>
      </div>

      <section className="history-section">
        <div className="section-heading">
          <div><span className="eyebrow">月份记录</span><h3>历史完成情况</h3></div>
          <button className="text-button" type="button">查看完整历史</button>
        </div>
        <div className="history-table">
          <div className="history-head"><span>月份</span><span>完成率</span><span>完成</span><span>延期</span><span>趋势</span></div>
          {stats.monthly.slice().reverse().map((item, index) => (
            <div className="history-row" key={item.month}>
              <strong>{item.month}</strong>
              <span>{item.completion}%</span>
              <span>{item.completed} 项</span>
              <span>{item.delayed} 项</span>
              <span className={index < stats.monthly.length - 1 ? 'positive' : ''}>{index === stats.monthly.length - 1 ? '—' : '↑ ' + (item.completion - stats.monthly[stats.monthly.length - 2 - index].completion) + '%'}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  )
}

function ReviewPage({
  plansData,
  calendarItems,
  onUpdatePlan,
  onUpdateSchedule,
}: {
  plansData: Plan[]
  calendarItems: CalendarItem[]
  onUpdatePlan: (plan: Plan) => void
  onUpdateSchedule: (item: CalendarItem) => void
}) {
  const [accepted, setAccepted] = useState<string[]>([])
  const [messages, setMessages] = useState<AiReviewMessage[]>([
    { role: 'assistant', content: '把今天的执行感受、遇到的阻力或想调整的安排告诉我。我会结合现有计划一起复盘。' },
  ])
  const [chatDraft, setChatDraft] = useState('')
  const [conversationId, setConversationId] = useState<string>()
  const [pendingChanges, setPendingChanges] = useState<AiPlanChange[]>([])
  const [chatBusy, setChatBusy] = useState(false)
  const [chatError, setChatError] = useState('')

  const sendReviewMessage = async () => {
    const content = chatDraft.trim()
    if (!content || chatBusy) return
    const userMessage: AiReviewMessage = { role: 'user', content }
    const history = [...messages, userMessage]
    setMessages(history)
    setChatDraft('')
    setChatBusy(true)
    setChatError('')
    try {
      const result = await plannerApi.chatReview(content, messages, conversationId)
      setConversationId(result.conversationId)
      setMessages((current) => [...current, { role: 'assistant', content: result.reply }])
      setPendingChanges(result.changes ?? [])
    } catch (error) {
      setChatError(error instanceof Error && error.message.includes('503')
        ? 'AI 尚未配置，请先在后端设置 PLANNER_AI_API_KEY。'
        : '暂时无法连接 AI，请稍后重试。')
    } finally {
      setChatBusy(false)
    }
  }

  const applyAiChanges = () => {
    pendingChanges.forEach((change) => {
      if (change.entity === 'plan') {
        const current = plansData.find((plan) => plan.id === change.id)
        if (!current) return
        const fields = change.fields
        onUpdatePlan({
          ...current,
          title: typeof fields.title === 'string' ? fields.title : current.title,
          subtitle: typeof fields.description === 'string' ? fields.description : current.subtitle,
          dueDate: typeof fields.dueDate === 'string' ? fields.dueDate : current.dueDate,
          progress: typeof fields.progress === 'number' ? Math.max(0, Math.min(100, fields.progress)) : current.progress,
          status: fields.status === 'active' || fields.status === 'paused' || fields.status === 'completed' ? fields.status : current.status,
        })
        return
      }
      const current = calendarItems.find((item) => item.id === change.id)
      if (!current) return
      const fields = change.fields
      const startAt = typeof fields.startAt === 'string' ? fields.startAt : `${current.date}T${current.time}:00`
      onUpdateSchedule({
        ...current,
        title: typeof fields.title === 'string' ? fields.title : current.title,
        date: startAt.slice(0, 10),
        time: startAt.slice(11, 16),
        duration: typeof fields.durationMinutes === 'number' ? fields.durationMinutes : current.duration,
        status: fields.status === 'pending' || fields.status === 'done' || fields.status === 'delayed' ? fields.status : current.status,
        planId: typeof fields.planId === 'string' ? fields.planId : current.planId,
      })
    })
    setMessages((current) => [...current, { role: 'assistant', content: `已应用 ${pendingChanges.length} 项调整。` }])
    setPendingChanges([])
  }
  const suggestions = [
    { id: 's1', title: '把晚间深度任务缩短到 60 分钟', reason: '过去 7 天，超过 90 分钟的晚间任务有 4 次延期。', impact: '影响未来 6 项任务' },
    { id: 's2', title: '周三晚上保留为缓冲时间', reason: '临时事项主要集中在周中，当前计划没有恢复空间。', impact: '移动 2 项任务' },
    { id: 's3', title: '优先完成首页交互再扩展功能', reason: '产品计划同时推进 3 个方向，核心链路完成度最高。', impact: '调整本周优先级' },
  ]

  return (
    <div className="review-page content-page">
      <section className="ai-chat-workspace">
        <div className="section-heading">
          <div><span className="eyebrow">AI 计划伙伴</span><h3>边复盘，边把计划调到合适的位置</h3></div>
          <span className="section-note">修改前需要你确认</span>
        </div>
        <div className="ai-chat-messages" aria-live="polite">
          {messages.map((message, index) => (
            <article className={`ai-chat-message ${message.role}`} key={`${message.role}-${index}`}>
              <span>{message.role === 'assistant' ? 'AI' : '你'}</span>
              <p>{message.content}</p>
            </article>
          ))}
          {chatBusy && <article className="ai-chat-message assistant loading"><span>AI</span><p>正在结合你的计划分析...</p></article>}
        </div>
        {pendingChanges.length > 0 && (
          <div className="ai-change-draft">
            <div className="ai-change-heading"><div><strong>计划调整草案</strong><span>{pendingChanges.length} 项变更，确认后才会执行</span></div><button className="primary-button" type="button" onClick={applyAiChanges}><Check size={16} /> 确认应用</button></div>
            {pendingChanges.map((change, index) => (
              <article key={`${change.id}-${index}`}><span>{change.entity === 'plan' ? '计划' : '日程'}</span><div><strong>{change.title}</strong><p>{change.summary}</p></div></article>
            ))}
            <button className="text-button" type="button" onClick={() => setPendingChanges([])}>放弃本次调整</button>
          </div>
        )}
        <div className="ai-chat-composer">
          <textarea value={chatDraft} onChange={(event) => setChatDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void sendReviewMessage() } }} placeholder="例如：我晚上总是完不成，帮我把这周安排得轻一点" rows={3} />
          <button className="primary-button" type="button" disabled={chatBusy || !chatDraft.trim()} onClick={() => void sendReviewMessage()}><ArrowRight size={16} /> 发送</button>
        </div>
        {chatError && <p className="ai-chat-error">{chatError}</p>}
      </section>
      <section className="review-date-row">
        <div>
          <span className="eyebrow">每日复盘</span>
          <h2>8 月 2 日，星期日</h2>
          <p>今天的执行事实已经整理完成。</p>
        </div>
        <button className="secondary-button" type="button"><CalendarDays size={16} /> 选择日期</button>
      </section>

      <section className="review-facts">
        <div><CheckCircle2 size={19} /><span>完成</span><strong>5 项</strong></div>
        <div><Clock3 size={19} /><span>专注</span><strong>3.2 小时</strong></div>
        <div><RefreshCw size={19} /><span>延期</span><strong>2 项</strong></div>
        <div><Target size={19} /><span>计划完成率</span><strong>71%</strong></div>
      </section>

      <div className="review-layout">
        <section className="ai-review">
          <div className="ai-review-heading">
            <div className="ai-mark"><Sparkles size={20} /></div>
            <div><span className="eyebrow">AI 复盘</span><h3>今天推进得比你感觉中更扎实</h3></div>
          </div>
          <div className="review-copy">
            <p>你完成了产品首页的信息结构，并明确了统计与复盘页面的边界。虽然有两项任务延期，但它们都属于后续视觉细节，没有阻塞核心路径。</p>
            <p>值得注意的是，你在上午的完成效率明显高于晚间。最近一周，上午安排的核心任务完成率是 86%，晚间超过 90 分钟的任务完成率只有 48%。</p>
          </div>
          <div className="review-insights">
            <article><TrendingUp size={18} /><div><strong>最有效的时段</strong><span>上午 09:00—11:30，适合核心设计与开发。</span></div></article>
            <article><Lightbulb size={18} /><div><strong>主要偏差</strong><span>晚间任务估时偏乐观，平均多安排了 37 分钟。</span></div></article>
            <article><Target size={18} /><div><strong>明日重点</strong><span>完成日历日期详情，不同时开启新的页面分支。</span></div></article>
          </div>
        </section>

        <section className="review-log">
          <div className="section-heading">
            <div><span className="eyebrow">执行记录</span><h3>今天发生了什么</h3></div>
          </div>
          {[
            ['09:20', '完成首页结构确认', 'product'],
            ['11:10', '完成暖色视觉方向', 'product'],
            ['15:30', '调整项目方案范围', 'learning'],
            ['20:40', '日历交互原型延期', 'delayed'],
          ].map((row) => (
            <article className="log-row" key={row[0]}>
              <span>{row[0]}</span>
              <i className={row[2]} />
              <strong>{row[1]}</strong>
            </article>
          ))}
        </section>
      </div>

      <section className="adjustments">
        <div className="section-heading">
          <div><span className="eyebrow">明日调整</span><h3>基于最近 7 天的建议</h3></div>
          <span className="section-note">应用后仍可撤销</span>
        </div>
        <div className="suggestion-list">
          {suggestions.map((suggestion) => {
            const isAccepted = accepted.includes(suggestion.id)
            return (
              <article className="suggestion-row" key={suggestion.id}>
                <div className="suggestion-icon"><BrainCircuit size={19} /></div>
                <div><strong>{suggestion.title}</strong><p>{suggestion.reason}</p><span>{suggestion.impact}</span></div>
                <button
                  className={isAccepted ? 'accepted-button' : 'secondary-button'}
                  type="button"
                  onClick={() => setAccepted((current) => isAccepted ? current.filter((id) => id !== suggestion.id) : [...current, suggestion.id])}
                >
                  {isAccepted ? <><Check size={16} /> 已采纳</> : '采纳建议'}
                </button>
              </article>
            )
          })}
        </div>
      </section>
    </div>
  )
}

function PlanDialog({ onClose, onSubmit }: { onClose: () => void; onSubmit: (plan: Plan) => void }) {
  const [draft, setDraft] = useState({ title: '', subtitle: '', dueDate: '2026-12-31', color: '#d39a24' })

  const submit = () => {
    if (!draft.title.trim()) return
    onSubmit({
      id: 'plan-' + Date.now(),
      title: draft.title.trim(),
      subtitle: draft.subtitle.trim() || '新的长期计划',
      progress: 0,
      color: draft.color,
      status: 'active',
      completedTasks: 0,
      totalTasks: 0,
      dueDate: draft.dueDate,
      items: [],
    })
  }

  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="editor-modal" role="dialog" aria-modal="true" aria-label="新建长期计划">
        <div className="modal-heading"><div><span className="eyebrow">长期目标</span><h2>新建长期计划</h2></div><button className="icon-button" type="button" onClick={onClose} title="关闭"><X size={18} /></button></div>
        <div className="modal-fields">
          <label>计划名称<input autoFocus value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} placeholder="例如：完成高等数学系统学习" /></label>
          <label>计划说明<input value={draft.subtitle} onChange={(event) => setDraft({ ...draft, subtitle: event.target.value })} placeholder="一句话说明目标" /></label>
          <div className="field-row"><label>目标日期<input type="date" value={draft.dueDate} onChange={(event) => setDraft({ ...draft, dueDate: event.target.value })} /></label><label>识别颜色<input type="color" value={draft.color} onChange={(event) => setDraft({ ...draft, color: event.target.value })} /></label></div>
        </div>
        <div className="modal-actions"><button className="secondary-button" type="button" onClick={onClose}>取消</button><button className="primary-button" type="button" onClick={submit}><Plus size={16} /> 创建计划</button></div>
      </section>
    </div>
  )
}

function ScheduleDialog({
  plansData,
  initialItem,
  defaultDate,
  defaultPlanId,
  onClose,
  onSubmit,
}: {
  plansData: Plan[]
  initialItem?: CalendarItem
  defaultDate?: string
  defaultPlanId?: string
  onClose: () => void
  onSubmit: (item: CalendarItem) => void
}) {
  const initialPlan = plansData.find((plan) => plan.id === (initialItem?.planId ?? defaultPlanId)) ?? plansData[0]
  const [draft, setDraft] = useState({
    title: initialItem?.title ?? '',
    date: initialItem?.date ?? defaultDate ?? '2026-08-02',
    time: initialItem?.time ?? '09:00',
    duration: initialItem?.duration ?? 60,
    planId: initialPlan?.id ?? '',
  })

  const submit = () => {
    if (!draft.title.trim() || !draft.planId) return
    const plan = plansData.find((value) => value.id === draft.planId)
    onSubmit({
      id: initialItem?.id ?? 'schedule-' + Date.now(),
      title: draft.title.trim(),
      date: draft.date,
      time: draft.time,
      duration: Math.max(15, Number(draft.duration) || 60),
      planId: draft.planId,
      color: plan?.color ?? '#d39a24',
      status: initialItem?.status ?? 'pending',
      kind: 'schedule',
    })
  }

  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="editor-modal" role="dialog" aria-modal="true" aria-label={initialItem ? '编辑日程安排' : '新建日程安排'}>
        <div className="modal-heading"><div><span className="eyebrow">落实到日历</span><h2>{initialItem ? '编辑日程安排' : '新建日程安排'}</h2></div><button className="icon-button" type="button" onClick={onClose} title="关闭"><X size={18} /></button></div>
        <div className="modal-fields">
          <label>安排标题<input autoFocus value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} placeholder="这段时间具体要完成什么" /></label>
          <label>所属计划<select value={draft.planId} onChange={(event) => setDraft({ ...draft, planId: event.target.value })}>{plansData.map((plan) => <option value={plan.id} key={plan.id}>{plan.title}</option>)}</select></label>
          <div className="field-row"><label>日期<input type="date" value={draft.date} onChange={(event) => setDraft({ ...draft, date: event.target.value })} /></label><label>开始时间<input type="time" value={draft.time} onChange={(event) => setDraft({ ...draft, time: event.target.value })} /></label></div>
          <label>预计分钟<input type="number" min="15" step="15" value={draft.duration} onChange={(event) => setDraft({ ...draft, duration: Number(event.target.value) })} /></label>
        </div>
        <div className="modal-actions"><button className="secondary-button" type="button" onClick={onClose}>取消</button><button className="primary-button" type="button" onClick={submit}><Save size={16} /> 保存安排</button></div>
      </section>
    </div>
  )
}

function TodoDialog({ onClose, onSubmit }: { onClose: () => void; onSubmit: (item: TodoItem) => void }) {
  const [draft, setDraft] = useState({ title: '', date: format(new Date(), 'yyyy-MM-dd'), time: '09:00', priority: '中' as TodoItem['priority'], reminder: '无提醒' })

  const submit = () => {
    if (!draft.title.trim()) return
    onSubmit({ id: 'todo-' + Date.now(), title: draft.title.trim(), date: draft.date, time: draft.time || '未安排', priority: draft.priority, done: false, reminder: draft.reminder })
  }

  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="editor-modal" role="dialog" aria-modal="true" aria-label="新建待办">
        <div className="modal-heading"><div><span className="eyebrow">一次性事项</span><h2>新建待办</h2></div><button className="icon-button" type="button" onClick={onClose} title="关闭"><X size={18} /></button></div>
        <div className="modal-fields">
          <label>待办内容<input autoFocus value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} placeholder="只需要完成一次的事" /></label>
          <div className="field-row"><label>日期<input type="date" value={draft.date} onChange={(event) => setDraft({ ...draft, date: event.target.value })} /></label><label>时间<input type="time" value={draft.time} onChange={(event) => setDraft({ ...draft, time: event.target.value })} /></label></div>
          <div className="field-row"><label>优先级<select value={draft.priority} onChange={(event) => setDraft({ ...draft, priority: event.target.value as TodoItem['priority'] })}><option>高</option><option>中</option><option>低</option></select></label><label>提醒<select value={draft.reminder} onChange={(event) => setDraft({ ...draft, reminder: event.target.value })}><option>无提醒</option><option>到点提醒</option><option>提前 30 分钟</option><option>提前 2 小时</option><option>提前 1 天</option></select></label></div>
        </div>
        <div className="modal-actions"><button className="secondary-button" type="button" onClick={onClose}>取消</button><button className="primary-button" type="button" onClick={submit}><CheckSquare2 size={16} /> 创建待办</button></div>
      </section>
    </div>
  )
}

function App() {
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
