import { useEffect, useState } from 'react'
import { format, parseISO } from 'date-fns'
import { Bell, CalendarDays, Check, CheckCircle2, Circle, PenLine, Plus, SquareCheckBig, Trash2, X } from 'lucide-react'
import type { TodoItem } from '../../types/planner'
import { TodoDialog } from '../../components/dialogs/PlannerDialogs'

/** 待办页面维护局部编辑状态，数据增删改仍由 App 统一协调。 */
export function TodosPage({
  todoItems,
  onCreate,
  onUpdate,
  onDelete,
  onDeleteMany,
  onToggle,
}: {
  todoItems: TodoItem[]
  onCreate: (item: TodoItem) => void
  onUpdate: (item: TodoItem) => void
  onDelete: (id: string) => void
  onDeleteMany: (ids: string[]) => Promise<boolean>
  onToggle: (id: string) => void
}) {
  const today = format(new Date(), 'yyyy-MM-dd')
  const [filter, setFilter] = useState<'全部' | '今天' | '已完成'>('全部')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [draft, setDraft] = useState<TodoItem | null>(null)
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [todoDialogOpen, setTodoDialogOpen] = useState(false)

  useEffect(() => {
    const ids = new Set(todoItems.map((item) => item.id))
    setSelectedIds((current) => current.filter((id) => ids.has(id)))
  }, [todoItems])

  const visibleItems = todoItems.filter((item) => {
    if (filter === '今天') return item.date === today
    if (filter === '已完成') return item.done
    return true
  })
  const visibleIds = visibleItems.map((item) => item.id)
  const allVisibleSelected = visibleIds.length > 0 && visibleIds.every((id) => selectedIds.includes(id))

  const displayDate = (date: string) => date === today ? '今天' : format(parseISO(date), 'M 月 d 日')

  const startEdit = (item: TodoItem) => { setEditingId(item.id); setDraft({ ...item }) }

  const saveEdit = () => {
    if (draft?.title.trim()) onUpdate({ ...draft, title: draft.title.trim() })
    setEditingId(null)
    setDraft(null)
  }

  const toggleSelection = (id: string) => {
    setSelectedIds((current) => current.includes(id) ? current.filter((value) => value !== id) : [...current, id])
  }

  const deleteSelected = async () => {
    if (selectedIds.length > 0 && await onDeleteMany(selectedIds)) setSelectedIds([])
  }

  return (
    <div className="todos-page content-page">
      <section className="todo-quick-add">
        <SquareCheckBig size={20} />
        <div className="todo-quick-copy"><strong>添加一次性待办</strong><span>设置日期、时间、优先级和提醒</span></div>
        <button className="primary-button" type="button" onClick={() => setTodoDialogOpen(true)}><Plus size={16} /> 添加待办</button>
      </section>

      <div className="todo-toolbar">
        <div className="segmented-control">
          {(['全部', '今天', '已完成'] as const).map((item) => (
            <button type="button" className={filter === item ? 'active' : ''} onClick={() => { setFilter(item); setSelectedIds([]) }} key={item}>{item}</button>
          ))}
        </div>
        <div className="batch-actions">
          {visibleItems.length > 0 && <label className="batch-select"><input className="batch-checkbox" type="checkbox" checked={allVisibleSelected} onChange={() => setSelectedIds(allVisibleSelected ? selectedIds.filter((id) => !visibleIds.includes(id)) : Array.from(new Set([...selectedIds, ...visibleIds])))} />全选当前</label>}
          {selectedIds.length > 0 && <button className="secondary-button danger-text" type="button" onClick={() => void deleteSelected()}><Trash2 size={15} /> 删除已选（{selectedIds.length}）</button>}
          <span className="batch-summary">待完成 {todoItems.filter((item) => !item.done).length} 项</span>
        </div>
      </div>

      <section className="todo-list">
        <div className="todo-list-head">
          <span><input className="batch-checkbox" type="checkbox" checked={allVisibleSelected} onChange={() => setSelectedIds(allVisibleSelected ? selectedIds.filter((id) => !visibleIds.includes(id)) : Array.from(new Set([...selectedIds, ...visibleIds])))} aria-label="全选当前待办" /></span><span>状态</span><span>待办事项</span><span>日期与时间</span><span>提醒</span><span>优先级</span><span />
        </div>
        {visibleItems.map((item) => (
            <article className={'todo-row ' + (item.done ? 'done ' : '') + (editingId === item.id ? 'editing' : '')} key={item.id}>
              <input className="batch-checkbox" type="checkbox" checked={selectedIds.includes(item.id)} onChange={() => toggleSelection(item.id)} aria-label={`选择待办：${item.title}`} />
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
                  <span className="todo-date"><CalendarDays size={14} /> {item.date ? `${displayDate(item.date)} · ${item.time}` : '未安排'}</span>
                  <span className="todo-reminder"><Bell size={14} /> {item.reminder}</span>
                  <b className={'todo-priority priority priority-' + item.priority}>{item.priority}</b>
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
      {todoDialogOpen && <TodoDialog onClose={() => setTodoDialogOpen(false)} onSubmit={(item) => { onCreate(item); setTodoDialogOpen(false) }} />}
    </div>
  )
}
