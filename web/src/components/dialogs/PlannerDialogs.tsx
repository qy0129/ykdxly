import { useState } from 'react'
import { format } from 'date-fns'
import { CheckSquare2, Plus, Save, X } from 'lucide-react'
import type { CalendarItem, Plan, TodoItem } from '../../types/planner'

/** 计划相关弹窗只负责收集表单数据，保存动作由 App 传入。 */
export function PlanDialog({ onClose, onSubmit }: { onClose: () => void; onSubmit: (plan: Plan) => void }) {
  const [draft, setDraft] = useState({ title: '', subtitle: '', dueDate: '2026-12-31', color: '#d39a24' })

  const submit = () => {
    if (!draft.title.trim()) return
    onSubmit({
      id: 'plan-' + Date.now(), title: draft.title.trim(), subtitle: draft.subtitle.trim() || '新的长期计划',
      progress: 0, taskProgress: 0, effortProgress: 0, color: draft.color, status: 'active', completedTasks: 0, totalTasks: 0,
      dueDate: draft.dueDate, items: [], version: 0,
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

export function ScheduleDialog({ plansData, initialItem, defaultDate, defaultPlanId, onClose, onSubmit }: {
  plansData: Plan[]
  initialItem?: CalendarItem
  defaultDate?: string
  defaultPlanId?: string
  onClose: () => void
  onSubmit: (item: CalendarItem) => void
}) {
  const initialPlan = plansData.find((plan) => plan.id === (initialItem?.planId ?? defaultPlanId)) ?? plansData[0]
  const [draft, setDraft] = useState({ title: initialItem?.title ?? '', date: initialItem?.date ?? defaultDate ?? '2026-08-02', time: initialItem?.time ?? '09:00', duration: initialItem?.duration ?? 60, planId: initialPlan?.id ?? '' })

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
