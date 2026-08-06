import { useEffect, useMemo, useState } from 'react'
import { addMonths, eachDayOfInterval, endOfMonth, endOfWeek, format, isSameDay, isSameMonth, parseISO, startOfMonth, startOfWeek, subMonths } from 'date-fns'
import { zhCN } from 'date-fns/locale'
import { CalendarDays, CheckCircle2, ChevronLeft, ChevronRight, Circle, Clock3, Plus, Trash2, X } from 'lucide-react'
import type { CalendarItem, Plan } from '../../types/planner'
import { getCalendarProgress, weekLabels } from './calendarModel'

/** 日历模块包含月视图和单日抽屉，外部只提供条目与操作回调。 */
export function DayDrawer({
  date,
  items,
  plansData,
  onClose,
  onToggleItem,
  onOpenItem,
  selectedIds,
  onToggleSelected,
  onToggleAll,
  onDeleteMany,
  onAdd,
}: {
  date: Date
  items: CalendarItem[]
  plansData: Plan[]
  onClose: () => void
  onToggleItem: (id: string) => void
  onOpenItem: (item: CalendarItem) => void
  selectedIds: Set<string>
  onToggleSelected: (id: string) => void
  onToggleAll: () => void
  onDeleteMany: (ids: string[]) => void
  onAdd: () => void
}) {
  const dateLabel = format(date, 'M月d日 EEEE', { locale: zhCN })
  const completed = items.filter((item) => item.status === 'done').length
  const totalMinutes = items.reduce((total, item) => total + item.duration, 0)
  const schedules = items.filter((item) => item.kind !== 'todo')
  const selectedSchedules = schedules.filter((item) => selectedIds.has(item.id))

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
      {schedules.length > 0 && (
        <div className="agenda-bulk-toolbar">
          <label>
            <input type="checkbox" checked={selectedSchedules.length === schedules.length} onChange={onToggleAll} />
            <span>全选日程</span>
          </label>
          <span>{selectedSchedules.length > 0 ? `已选 ${selectedSchedules.length} 条` : '可批量删除'}</span>
          {selectedSchedules.length > 0 && (
            <button className="icon-button danger-button" type="button" title="删除选中日程" onClick={() => onDeleteMany(selectedSchedules.map((item) => item.id))}>
              <Trash2 size={16} />
            </button>
          )}
        </div>
      )}
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
            {item.kind !== 'todo' ? (
              <input
                className="agenda-checkbox"
                type="checkbox"
                aria-label="选择日程"
                checked={selectedIds.has(item.id)}
                onChange={() => onToggleSelected(item.id)}
                onClick={(event) => event.stopPropagation()}
              />
            ) : <span className="agenda-select-spacer" />}
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

export function CalendarPage({
  items,
  plansData,
  onToggleItem,
  onOpenItem,
  onDeleteMany,
  onAdd,
}: {
  items: CalendarItem[]
  plansData: Plan[]
  onToggleItem: (id: string) => void
  onOpenItem: (item: CalendarItem) => void
  onDeleteMany: (ids: string[]) => void
  onAdd: (date: Date) => void
}) {
  const today = new Date()
  const [month, setMonth] = useState(() => new Date())
  const [selectedDate, setSelectedDate] = useState<Date | null>(() => new Date())
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())

  useEffect(() => {
    const visibleIds = new Set(items.filter((item) => item.kind !== 'todo').map((item) => item.id))
    setSelectedIds((current) => new Set([...current].filter((id) => visibleIds.has(id))))
  }, [items])

  const days = useMemo(() => {
    const start = startOfWeek(startOfMonth(month), { weekStartsOn: 1 })
    const end = endOfWeek(endOfMonth(month), { weekStartsOn: 1 })
    return eachDayOfInterval({ start, end })
  }, [month])

  const itemsForDate = (date: Date) =>
    items.filter((item) => isSameDay(parseISO(item.date), date))

  const selectedItems = selectedDate ? itemsForDate(selectedDate) : []
  const selectedSchedules = selectedItems.filter((item) => item.kind !== 'todo')
  const toggleSelected = (id: string) => setSelectedIds((current) => {
    const next = new Set(current)
    if (next.has(id)) next.delete(id); else next.add(id)
    return next
  })
  const toggleAllSelected = () => setSelectedIds((current) => {
    const next = new Set(current)
    const allSelected = selectedSchedules.every((item) => next.has(item.id))
    selectedSchedules.forEach((item) => allSelected ? next.delete(item.id) : next.add(item.id))
    return next
  })
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
            const dayItems = itemsForDate(day)
            const selected = selectedDate && isSameDay(day, selectedDate)
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
            selectedIds={selectedIds}
            onToggleSelected={toggleSelected}
            onToggleAll={toggleAllSelected}
            onDeleteMany={(ids) => {
              onDeleteMany(ids)
              setSelectedIds((current) => new Set([...current].filter((id) => !ids.includes(id))))
            }}
            onAdd={() => onAdd(selectedDate)}
          />
        )}
      </div>
    </div>
  )
}
