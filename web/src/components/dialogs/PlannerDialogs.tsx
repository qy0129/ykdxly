import { useState } from 'react'
import { addMonths, format } from 'date-fns'
import { CheckSquare2, Plus, Save, Trash2, X } from 'lucide-react'
import type { CalendarItem, Plan, PlanItem, PlanTask, TaskRecurrenceType, TodoItem } from '../../types/planner'

export type StageDraftFields = { title: string; dueLabel: string }
export type TaskDraftFields = {
  title: string
  estimatedMinutes: number
  dueAt: string
  recurrenceType: TaskRecurrenceType
  scheduleStartDate: string
  recurrenceEndDate: string
  scheduledTime: string
}
export type NoteDraftFields = { title: string; category: string; content: string }

export function ConfirmDeleteDialog({ message, onClose, onConfirm }: { message: string; onClose: () => void; onConfirm: () => void }) {
  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="editor-modal confirm-modal" role="dialog" aria-modal="true" aria-label="确认是否删除">
        <div className="modal-heading"><div><span className="eyebrow">删除操作</span><h2>确认是否删除</h2></div><button className="icon-button" type="button" onClick={onClose} title="关闭"><X size={18} /></button></div>
        <div className="confirm-modal-content"><Trash2 size={22} /><p>{message}</p></div>
        <div className="modal-actions"><button className="secondary-button" type="button" onClick={onClose}>取消</button><button className="primary-button danger-button" type="button" onClick={onConfirm}><Trash2 size={16} /> 确认删除</button></div>
      </section>
    </div>
  )
}

export function StageDialog({ initial, onClose, onSubmit }: { initial?: PlanItem; onClose: () => void; onSubmit: (fields: StageDraftFields) => void }) {
  const [draft, setDraft] = useState<StageDraftFields>({ title: initial?.title ?? '', dueLabel: initial?.dueLabel === '待安排' ? '' : initial?.dueLabel ?? '' })

  const submit = () => {
    if (!draft.title.trim()) return
    onSubmit({ title: draft.title.trim(), dueLabel: draft.dueLabel })
  }

  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="editor-modal" role="dialog" aria-modal="true" aria-label={initial ? '编辑计划阶段' : '添加计划阶段'}>
        <div className="modal-heading"><div><span className="eyebrow">推进路径</span><h2>{initial ? '编辑计划阶段' : '添加计划阶段'}</h2></div><button className="icon-button" type="button" onClick={onClose} title="关闭"><X size={18} /></button></div>
        <div className="modal-fields">
          <label>阶段名称<input autoFocus value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} placeholder="例如：完成资料收集" /></label>
          <label>截止日期<input type="date" value={draft.dueLabel} onChange={(event) => setDraft({ ...draft, dueLabel: event.target.value })} /></label>
        </div>
        <div className="modal-actions"><button className="secondary-button" type="button" onClick={onClose}>取消</button><button className="primary-button" type="button" onClick={submit}>{initial ? <Save size={16} /> : <Plus size={16} />} {initial ? '保存阶段' : '添加阶段'}</button></div>
      </section>
    </div>
  )
}

export function TaskDialog({ stageTitle, initial, onClose, onSubmit }: { stageTitle: string; initial?: PlanTask; onClose: () => void; onSubmit: (fields: TaskDraftFields) => Promise<boolean | void> | boolean | void }) {
  const defaultDate = initial?.scheduleStartDate ?? initial?.dueAt?.slice(0, 10) ?? format(new Date(), 'yyyy-MM-dd')
  const [draft, setDraft] = useState({
    title: initial?.title ?? '',
    estimatedMinutes: initial?.estimatedMinutes ?? 60,
    recurrenceType: initial?.recurrenceType ?? 'once' as TaskRecurrenceType,
    scheduleStartDate: defaultDate,
    recurrenceEndDate: initial?.recurrenceEndDate ?? initial?.dueAt?.slice(0, 10) ?? defaultDate,
    scheduledTime: initial?.scheduledTime?.slice(0, 5) ?? '09:00',
  })
  const [submitting, setSubmitting] = useState(false)

  const submit = async () => {
    if (submitting || !draft.title.trim() || !draft.scheduleStartDate || !draft.scheduledTime
      || (draft.recurrenceType !== 'once' && !draft.recurrenceEndDate)) return
      setSubmitting(true)
      try {
        const endDate = draft.recurrenceType === 'once' ? draft.scheduleStartDate : draft.recurrenceEndDate
        await onSubmit({
          title: draft.title.trim(),
          estimatedMinutes: Math.max(1, draft.estimatedMinutes || 60),
          recurrenceType: draft.recurrenceType,
          scheduleStartDate: draft.scheduleStartDate,
          recurrenceEndDate: endDate,
          scheduledTime: draft.scheduledTime,
          dueAt: `${endDate}T23:59:00`,
        })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="editor-modal" role="dialog" aria-modal="true" aria-label={initial ? '编辑计划任务' : '添加计划任务'}>
        <div className="modal-heading"><div><span className="eyebrow">{stageTitle}</span><h2>{initial ? '编辑计划任务' : '添加计划任务'}</h2></div><button className="icon-button" type="button" onClick={onClose} disabled={submitting} title="关闭"><X size={18} /></button></div>
          <div className="modal-fields">
            <label>任务名称<input autoFocus value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} placeholder="这项任务具体要完成什么" /></label>
            <div className="field-row"><label>预计分钟<input type="number" min="1" step="15" value={draft.estimatedMinutes} onChange={(event) => setDraft({ ...draft, estimatedMinutes: Number(event.target.value) })} /></label><label>执行频率<select value={draft.recurrenceType} onChange={(event) => setDraft({ ...draft, recurrenceType: event.target.value as TaskRecurrenceType })}><option value="once">仅一次</option><option value="daily">每天</option><option value="every_other_day">隔一天</option><option value="weekdays">工作日</option><option value="weekly">每周</option></select></label></div>
            <div className="field-row"><label>开始日期<input type="date" value={draft.scheduleStartDate} onChange={(event) => setDraft({ ...draft, scheduleStartDate: event.target.value, recurrenceEndDate: draft.recurrenceType === 'once' || draft.recurrenceEndDate < event.target.value ? event.target.value : draft.recurrenceEndDate })} /></label><label>结束日期<input type="date" value={draft.recurrenceType === 'once' ? draft.scheduleStartDate : draft.recurrenceEndDate} disabled={draft.recurrenceType === 'once'} min={draft.scheduleStartDate} onChange={(event) => setDraft({ ...draft, recurrenceEndDate: event.target.value })} /></label></div>
            <label>执行时间<input type="time" value={draft.scheduledTime} onChange={(event) => setDraft({ ...draft, scheduledTime: event.target.value })} /></label>
          </div>
        <div className="modal-actions"><button className="secondary-button" type="button" onClick={onClose} disabled={submitting}>取消</button><button className="primary-button" type="button" onClick={() => void submit()} disabled={submitting}>{initial ? <Save size={16} /> : <Plus size={16} />} {submitting ? '保存中…' : initial ? '保存任务' : '添加任务'}</button></div>
      </section>
    </div>
  )
}

export function NoteDialog({ initialCategory, categories, onClose, onSubmit }: { initialCategory?: string; categories: string[]; onClose: () => void; onSubmit: (fields: NoteDraftFields) => void }) {
  const [draft, setDraft] = useState<NoteDraftFields>({ title: '', category: initialCategory && initialCategory !== '全部笔记' ? initialCategory : '学习笔记', content: '' })
  const categoryOptions = Array.from(new Set(['学习笔记', ...categories.filter((category) => category !== '全部笔记')]))

  const submit = () => {
    if (!draft.title.trim()) return
    onSubmit({ ...draft, title: draft.title.trim() })
  }

  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="editor-modal" role="dialog" aria-modal="true" aria-label="新建笔记">
        <div className="modal-heading"><div><span className="eyebrow">知识库</span><h2>新建笔记</h2></div><button className="icon-button" type="button" onClick={onClose} title="关闭"><X size={18} /></button></div>
        <div className="modal-fields">
          <label>笔记标题<input autoFocus value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} placeholder="例如：本周复盘方法" /></label>
          <label>所属分类<select value={draft.category} onChange={(event) => setDraft({ ...draft, category: event.target.value })}>{categoryOptions.map((category) => <option key={category}>{category}</option>)}</select></label>
          <label>初始内容<textarea rows={5} value={draft.content} onChange={(event) => setDraft({ ...draft, content: event.target.value })} placeholder="记录这条笔记的核心内容" /></label>
        </div>
        <div className="modal-actions"><button className="secondary-button" type="button" onClick={onClose}>取消</button><button className="primary-button" type="button" onClick={submit}><Plus size={16} /> 创建笔记</button></div>
      </section>
    </div>
  )
}

export function CategoryDialog({ onClose, onSubmit }: { onClose: () => void; onSubmit: (category: string, color: string) => void }) {
  const [category, setCategory] = useState('')
  const [color, setColor] = useState('#7c647d')

  const submit = () => {
    if (!category.trim()) return
    onSubmit(category.trim(), color)
  }

  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="editor-modal" role="dialog" aria-modal="true" aria-label="新建笔记分类">
        <div className="modal-heading"><div><span className="eyebrow">知识库</span><h2>新建分类</h2></div><button className="icon-button" type="button" onClick={onClose} title="关闭"><X size={18} /></button></div>
        <div className="modal-fields">
          <label>分类名称<input autoFocus value={category} onChange={(event) => setCategory(event.target.value)} onKeyDown={(event) => event.key === 'Enter' && submit()} placeholder="例如：项目复盘" /></label>
          <label>节点颜色<input className="category-dialog-color" type="color" value={color} onChange={(event) => setColor(event.target.value)} /></label>
        </div>
        <div className="modal-actions"><button className="secondary-button" type="button" onClick={onClose}>取消</button><button className="primary-button" type="button" onClick={submit}><Plus size={16} /> 创建分类</button></div>
      </section>
    </div>
  )
}

/** 计划相关弹窗只负责收集表单数据，保存动作由 App 传入。 */
export function PlanDialog({ initial, onClose, onSubmit }: { initial?: Plan; onClose: () => void; onSubmit: (plan: Plan) => Promise<boolean | void> | boolean | void }) {
  const [draft, setDraft] = useState({ title: initial?.title ?? '', subtitle: initial?.subtitle ?? '', dueDate: initial?.dueDate || format(addMonths(new Date(), 3), 'yyyy-MM-dd'), color: initial?.color ?? '#d39a24' })
  const [submitting, setSubmitting] = useState(false)

  const submit = async () => {
    if (submitting) return
    if (!draft.title.trim()) return
    setSubmitting(true)
    try {
      await onSubmit(initial ? { ...initial, title: draft.title.trim(), subtitle: draft.subtitle.trim() || '新的长期计划', dueDate: draft.dueDate, color: draft.color } : {
        id: 'plan-' + Date.now(), title: draft.title.trim(), subtitle: draft.subtitle.trim() || '新的长期计划',
        progress: 0, taskProgress: 0, effortProgress: 0, color: draft.color, status: 'active', completedTasks: 0, totalTasks: 0,
        dueDate: draft.dueDate, items: [], version: 0,
      })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="editor-modal" role="dialog" aria-modal="true" aria-label={initial ? '编辑长期计划' : '新建长期计划'}>
        <div className="modal-heading"><div><span className="eyebrow">长期目标</span><h2>{initial ? '编辑长期计划' : '新建长期计划'}</h2></div><button className="icon-button" type="button" onClick={onClose} disabled={submitting} title="关闭"><X size={18} /></button></div>
        <div className="modal-fields">
          <label>计划名称<input autoFocus value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} placeholder="例如：完成高等数学系统学习" /></label>
          <label>计划说明<input value={draft.subtitle} onChange={(event) => setDraft({ ...draft, subtitle: event.target.value })} placeholder="一句话说明目标" /></label>
          <div className="field-row"><label>目标日期<input type="date" value={draft.dueDate} onChange={(event) => setDraft({ ...draft, dueDate: event.target.value })} /></label><label>识别颜色<input type="color" value={draft.color} onChange={(event) => setDraft({ ...draft, color: event.target.value })} /></label></div>
        </div>
        <div className="modal-actions"><button className="secondary-button" type="button" onClick={onClose} disabled={submitting}>取消</button><button className="primary-button" type="button" onClick={() => void submit()} disabled={submitting}>{initial ? <Save size={16} /> : <Plus size={16} />} {submitting ? '保存中…' : initial ? '保存计划' : '创建计划'}</button></div>
      </section>
    </div>
  )
}

export function ScheduleDialog({ plansData, initialItem, defaultDate, defaultPlanId, onClose, onSubmit }: {
  plansData: Plan[]
  initialItem?: CalendarItem
  defaultDate?: string
  defaultPlanId?: string
  onClose: () => void
  onSubmit: (item: CalendarItem) => void
}) {
  const initialPlan = plansData.find((plan) => plan.id === (initialItem?.planId ?? defaultPlanId)) ?? plansData[0]
  const [draft, setDraft] = useState({ title: initialItem?.title ?? '', date: initialItem?.date ?? defaultDate ?? format(new Date(), 'yyyy-MM-dd'), time: initialItem?.time ?? '09:00', duration: initialItem?.duration ?? 60, planId: initialPlan?.id ?? '' })

  const submit = () => {
    if (!draft.title.trim() || !draft.planId) return
    const plan = plansData.find((value) => value.id === draft.planId)
    onSubmit({ id: initialItem?.id ?? 'schedule-' + Date.now(), title: draft.title.trim(), date: draft.date, time: draft.time, duration: Math.max(15, Number(draft.duration) || 60), planId: draft.planId, color: plan?.color ?? '#d39a24', status: initialItem?.status ?? 'pending', kind: 'schedule' })
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

export function TodoDialog({ onClose, onSubmit }: { onClose: () => void; onSubmit: (item: TodoItem) => void }) {
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
