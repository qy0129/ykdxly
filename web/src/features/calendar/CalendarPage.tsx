import { useMemo, useState } from 'react'
import { addMonths, eachDayOfInterval, endOfMonth, endOfWeek, format, isSameDay, isSameMonth, parseISO, startOfMonth, startOfWeek, subMonths } from 'date-fns'
import { zhCN } from 'date-fns/locale'
import { CalendarDays, CheckCircle2, ChevronLeft, ChevronRight, Circle, Clock3, Plus, X } from 'lucide-react'
import type { CalendarItem, Plan } from '../../types/planner'
import { weekLabels } from './calendarModel'

/** 完成率小圆环：当天完成项 / 全部项。 */
function CompletionDonut({ done, total, size = 24 }: { done: number; total: number; size?: number }) {
  const value = total > 0 ? Math.min(100, Math.round((done / total) * 100)) : 0
  const radius = (size - 5) / 2
  const circumference = 2 * Math.PI * radius
  const dash = (value / 100) * circumference
  const center = size / 2
  return (
    <svg className="day-donut" width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={`完成率 ${value}%`}>
      <circle cx={center} cy={center} r={radius} fill="none" stroke="var(--border, #e5e7eb)" strokeWidth="4" />
      <circle
        cx={center} cy={center} r={radius} fill="none" stroke="var(--accent, #d39a24)" strokeWidth="4"
        strokeLinecap="round" strokeDasharray={`${dash} ${circumference - dash}`}
        transform={`rotate(-90 ${center} ${center})`}
      />
      <text x="50%" y="54%" textAnchor="middle" dominantBaseline="middle" fontSize={size / 3.4} fill="currentColor">{value}%</text>
    </svg>
  )
}

/** 日历模块包含月视图和单日抽屉，外部只提供条目与操作回调。 */
function AgendaRow({ item, onToggleItem, onOpenItem }: { item: CalendarItem; onToggleItem: (id: string) => void; onOpenItem: (item: CalendarItem) => void }) {
  return (
    <article className={'agenda-item ' + (item.status === 'done' ? 'done' : '')}>
      <button type="button" onClick={() => onToggleItem(item.id)} aria-label="切换完成状态">
        {item.status === 'done' ? <CheckCircle2 size={20} /> : <Circle size={20} />}
      </button>
      <button className="agenda-open" type="button" onClick={() => onOpenItem(item)}>
        <span className="agenda-title"><strong>{item.title}</strong><b>{item.time}</b></span>
        {item.description ? <span className="agenda-desc">{item.description}</span> : null}
        <span className="agenda-meta"><Clock3 size={13} /> {item.duration} 分钟 {item.kind === 'todo' ? '· 一次性待办' : ''}</span>
      </button>
      <i style={{ backgroundColor: item.color }} />
    </article>
  )
}

export function DayDrawer({
  date,
  items,
  onClose,
  onToggleItem,
  onOpenItem,
  onAdd,
}: {
  date: Date
  items: CalendarItem[]
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
          <span className="eyebrow">今日计划</span>
          <h2>{dateLabel}</h2>
        </div>
        <button className="icon-button" type="button" onClick={onClose} title="关闭">
          <X size={19} />
        </button>
      </div>
      <div className="day-summary">
        <div className="day-summary-donut"><CompletionDonut done={completed} total={items.length} size={44} /></div>
        <div><strong>{completed}/{items.length}</strong><span>已完成</span></div>
        <div><strong>{Math.round(totalMinutes / 30) / 2}</strong><span>预计小时</span></div>
      </div>
      <div className="day-agenda">
        {items.length === 0 ? (
          <div className="empty-day">
            <CalendarDays size={28} />
            <strong>今天还没有安排</strong>
            <span>给长期计划留出一个具体时间。</span>
          </div>
        ) : items.map((item) => <AgendaRow key={item.id} item={item} onToggleItem={onToggleItem} onOpenItem={onOpenItem} />)}
      </div>
      <button className="drawer-add" type="button" onClick={onAdd}>
        <Plus size={17} /> 添加当天安排
      </button>
    </aside>
  )
}

export function CalendarPage({
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
  const today = new Date()
  const [month, setMonth] = useState(() => new Date())
  const [selectedDate, setSelectedDate] = useState<Date | null>(() => new Date())

  const days = useMemo(() => {
    const start = startOfWeek(startOfMonth(month), { weekStartsOn: 1 })
    const end = endOfWeek(endOfMonth(month), { weekStartsOn: 1 })
    return eachDayOfInterval({ start, end })
  }, [month])

  // 按时间稳定排序：完成与否不改变顺序，避免勾选后因 updated_at 变化导致列表上下跳动。
  const itemsForDate = (date: Date) =>
    items.filter((item) => isSameDay(parseISO(item.date), date))
      .sort((left, right) => left.time.localeCompare(right.time))

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
          <button className="text-button" type="button" onClick={() => { const current = new Date(); setMonth(current); setSelectedDate(current) }}>今天</button>
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
            const selected = selectedDate && isSameDay(day, selectedDate)
            const dayItems = itemsForDate(day)
            const doneCount = dayItems.filter((item) => item.status === 'done').length
            return (
              <button
                type="button"
                className={[
                  'day-cell',
                  !isSameMonth(day, month) ? 'outside-month' : '',
                  selected ? 'selected' : '',
                  isSameDay(day, today) ? 'today' : '',
                ].filter(Boolean).join(' ')}
                key={day.toISOString()}
                onClick={() => setSelectedDate(day)}
              >
                <span className="day-number">{format(day, 'd')}</span>
                {dayItems.length > 0 ? (
                  <span className="day-events-scroll" onClick={(event) => { event.stopPropagation(); setSelectedDate(day) }}>
                    {dayItems.map((item) => (
                      <span
                        className={'calendar-event ' + (item.status === 'done' ? 'done' : '')}
                        style={{ borderLeftColor: item.color }}
                        key={item.id}
                      >
                        <i style={{ backgroundColor: item.color }} /><b>{item.time}</b><em>{item.title}</em>
                      </span>
                    ))}
                  </span>
                ) : null}
                {dayItems.length > 0 ? (
                  <span className="day-progress-track" aria-label={`完成率 ${Math.round(doneCount * 100 / dayItems.length)}%`}>
                    <i style={{ width: `${Math.round(doneCount * 100 / dayItems.length)}%` }} />
                  </span>
                ) : null}
              </button>
            )
          })}
        </div>
        {selectedDate && (
          <DayDrawer
            date={selectedDate}
            items={selectedItems}
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
