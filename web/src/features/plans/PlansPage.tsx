import { useState } from 'react'
import { Check, Circle, PenLine, Plus, Sparkles, X } from 'lucide-react'
import type { CalendarItem, Plan, PlanItem } from '../../types/planner'
import { ProgressRing } from '../../components/ui/ProgressRing'

/** 计划页面负责计划与阶段的展示编辑，持久化动作交给上层。 */
export function PlansPage({
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

