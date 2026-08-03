import { useState } from 'react'
import { Ban, Check, CheckCircle2, Circle, Clock3, PenLine, Plus, Sparkles, X } from 'lucide-react'
import type { CalendarItem, Plan, PlanItem, PlanTask } from '../../types/planner'
import { ProgressRing } from '../../components/ui/ProgressRing'

/** 计划页按阶段展示可执行任务；阶段和计划进度均由任务状态计算。 */
export function PlansPage({
  activePlanId, plansData, calendarItems, onPlanChange, onUpdatePlan, onDeletePlan,
  onCreateStage, onUpdateStage, onDeleteStage, onCreateTask, onUpdateTask,
  onTaskAction, onDeleteTask, onAddSchedule, onAiAdjust,
}: {
  activePlanId: string
  plansData: Plan[]
  calendarItems: CalendarItem[]
  onPlanChange: (id: string) => void
  onUpdatePlan: (plan: Plan) => void
  onDeletePlan: (id: string) => void
  onCreateStage: (planId: string, item: PlanItem) => void
  onUpdateStage: (planId: string, item: PlanItem) => void
  onDeleteStage: (planId: string, item: PlanItem) => void
  onCreateTask: (planId: string, stageId: string, fields: { title: string; estimatedMinutes?: number; dueAt?: string }) => void
  onUpdateTask: (task: PlanTask) => void
  onTaskAction: (task: PlanTask, action: 'complete' | 'delay' | 'block' | 'skip' | 'cancel' | 'reopen', fields?: Record<string, unknown>) => Promise<PlanTask | undefined>
  onDeleteTask: (task: PlanTask) => void
  onAddSchedule: (planId: string) => void
  onAiAdjust: (plan: Plan) => void
}) {
  const plan = plansData.find((item) => item.id === activePlanId) ?? plansData[0]
  const [editingPlan, setEditingPlan] = useState(false)
  const [planDraft, setPlanDraft] = useState({ title: '', subtitle: '', dueDate: '', color: '#d39a24' })
  const [editingStageId, setEditingStageId] = useState<string | null>(null)
  const [stageDraft, setStageDraft] = useState({ title: '', dueLabel: '' })
  const [taskStageId, setTaskStageId] = useState<string | null>(null)
  const [taskDraft, setTaskDraft] = useState({ title: '', estimatedMinutes: 60, dueAt: '' })
  const [resultTask, setResultTask] = useState<PlanTask | null>(null)
  const [resultDraft, setResultDraft] = useState({ actualMinutes: '', reason: '' })

  if (!plan) return <div className="content-page simple-empty">还没有长期计划，先创建一个计划吧。</div>
  const nextItems = calendarItems.filter((item) => item.planId === plan.id).slice(0, 5)

  const startPlanEdit = () => {
    setPlanDraft({ title: plan.title, subtitle: plan.subtitle, dueDate: plan.dueDate, color: plan.color })
    setEditingPlan(true)
  }

  const startStageEdit = (item: PlanItem) => {
    setEditingStageId(item.id)
    setStageDraft({ title: item.title, dueLabel: item.dueLabel })
  }

  const saveStage = () => {
    if (!stageDraft.title.trim()) return
    const existing = plan.items.find((item) => item.id === editingStageId)
    const item: PlanItem = existing
      ? { ...existing, title: stageDraft.title.trim(), dueLabel: stageDraft.dueLabel.trim() || '待安排' }
      : { id: `stage-${Date.now()}`, title: stageDraft.title.trim(), dueLabel: stageDraft.dueLabel.trim() || '待安排', progress: 0, taskProgress: 0, effortProgress: 0, version: 0, tasks: [] }
    if (existing) onUpdateStage(plan.id, item); else onCreateStage(plan.id, item)
    setEditingStageId(null); setStageDraft({ title: '', dueLabel: '' })
  }

  const createTask = (stageId: string) => {
    if (!taskDraft.title.trim()) return
    onCreateTask(plan.id, stageId, {
      title: taskDraft.title.trim(), estimatedMinutes: Math.max(1, taskDraft.estimatedMinutes || 60),
      dueAt: taskDraft.dueAt ? `${taskDraft.dueAt}T23:59:00` : undefined,
    })
    setTaskStageId(null); setTaskDraft({ title: '', estimatedMinutes: 60, dueAt: '' })
  }

  const completeTask = async (task: PlanTask) => {
    const completed = await onTaskAction(task, 'complete')
    if (completed) { setResultTask(completed); setResultDraft({ actualMinutes: '', reason: '' }) }
  }

  return (
    <div className="plans-page content-page">
      <div className="plans-switcher" role="tablist" aria-label="选择长期计划">
        {plansData.map((item) => <button type="button" role="tab" aria-selected={item.id === plan.id} className={item.id === plan.id ? 'active' : ''} key={item.id} onClick={() => onPlanChange(item.id)}><i style={{ backgroundColor: item.color }} /><span>{item.title}</span><b>{item.taskProgress}%</b></button>)}
      </div>

      <section className="plan-overview">
        <div className="plan-overview-main">
          <ProgressRing value={plan.taskProgress} color={plan.color} size={76} />
          <div><span className="eyebrow">长期计划</span><h2>{plan.title}</h2><p>{plan.completedTasks} 项已完成，共 {plan.totalTasks} 项 · 目标日期 {plan.dueDate || '待安排'}</p><small>任务完成率 {plan.taskProgress}% · 预计工时完成率 {plan.effortProgress}%</small></div>
        </div>
        <div className="overview-actions">
          <button className="secondary-button" type="button" onClick={() => onAiAdjust(plan)}><Sparkles size={17} /> AI 调整</button>
          <button className="secondary-button" type="button" onClick={startPlanEdit}><PenLine size={16} /> 编辑计划</button>
          <button className="primary-button" type="button" onClick={() => onAddSchedule(plan.id)}><Plus size={17} /> 添加安排</button>
        </div>
      </section>

      {editingPlan && <section className="inline-editor plan-inline-editor">
        <div className="inline-editor-fields">
          <label>计划名称<input value={planDraft.title} onChange={(event) => setPlanDraft({ ...planDraft, title: event.target.value })} /></label>
          <label>副标题<input value={planDraft.subtitle} onChange={(event) => setPlanDraft({ ...planDraft, subtitle: event.target.value })} /></label>
          <label>目标日期<input type="date" value={planDraft.dueDate} onChange={(event) => setPlanDraft({ ...planDraft, dueDate: event.target.value })} /></label>
          <label>颜色<input type="color" value={planDraft.color} onChange={(event) => setPlanDraft({ ...planDraft, color: event.target.value })} /></label>
        </div>
        <span className="row-actions"><button className="primary-button" type="button" onClick={() => { onUpdatePlan({ ...plan, ...planDraft }); setEditingPlan(false) }}><Check size={15} /> 保存</button><button className="secondary-button" type="button" onClick={() => setEditingPlan(false)}>取消</button><button className="text-button danger-text" type="button" onClick={() => onDeletePlan(plan.id)}>删除计划</button></span>
      </section>}

      <div className="plan-detail-grid">
        <section className="plan-stages">
          <div className="section-heading"><div><span className="eyebrow">阶段与任务</span><h3>推进路径</h3></div><button className="secondary-button" type="button" onClick={() => { setEditingStageId('new'); setStageDraft({ title: '', dueLabel: '' }) }}><Plus size={15} /> 添加阶段</button></div>
          {plan.items.map((stage, index) => <section className="stage-task-group" key={stage.id}>
            <article className="stage-row">
              <span className="stage-index">{String(index + 1).padStart(2, '0')}</span>
              {editingStageId === stage.id ? <div className="stage-edit-fields"><input value={stageDraft.title} onChange={(event) => setStageDraft({ ...stageDraft, title: event.target.value })} /><input type="date" value={stageDraft.dueLabel === '待安排' ? '' : stageDraft.dueLabel} onChange={(event) => setStageDraft({ ...stageDraft, dueLabel: event.target.value })} /></div> : <div className="stage-copy"><strong>{stage.title}</strong><span>{stage.dueLabel} · 任务 {stage.taskProgress}% · 工时 {stage.effortProgress}%</span><div className="stage-track"><i style={{ width: `${stage.taskProgress}%`, backgroundColor: plan.color }} /></div></div>}
              {editingStageId === stage.id ? <span className="row-actions"><button className="icon-button" type="button" onClick={saveStage} title="保存"><Check size={15} /></button><button className="icon-button" type="button" onClick={() => setEditingStageId(null)} title="取消"><X size={15} /></button></span> : <span className="row-actions"><b>{stage.taskProgress}%</b><button className="icon-button" type="button" onClick={() => startStageEdit(stage)} title="编辑阶段"><PenLine size={14} /></button><button className="icon-button danger-icon" type="button" onClick={() => onDeleteStage(plan.id, stage)} title="删除阶段"><X size={14} /></button></span>}
            </article>
            <div className="plan-task-list">
              {stage.tasks.map((task) => <article className={`plan-task-row ${task.status}`} key={task.id}>
                <button className="icon-button" type="button" onClick={() => { if (task.status === 'done') void onTaskAction(task, 'reopen'); else void completeTask(task) }} title={task.status === 'done' ? '恢复待完成' : '完成任务'}>{task.status === 'done' ? <CheckCircle2 size={18} /> : <Circle size={18} />}</button>
                <div><strong>{task.title}</strong><span>{task.estimatedMinutes ? `预计 ${task.estimatedMinutes} 分钟` : '未估时'}{task.dueAt ? ` · ${task.dueAt.slice(0, 10)}` : ''}{task.reason ? ` · ${task.reason}` : ''}</span></div>
                <span className="task-status">{task.status}</span>
                <button className="icon-button" type="button" onClick={() => { const reason = window.prompt('阻塞原因'); if (reason) void onTaskAction(task, 'block', { reason }) }} title="标记阻塞"><Ban size={15} /></button>
                <button className="icon-button danger-icon" type="button" onClick={() => onDeleteTask(task)} title="移入回收站"><X size={15} /></button>
              </article>)}
              {taskStageId === stage.id ? <div className="stage-new-form"><input autoFocus value={taskDraft.title} onChange={(event) => setTaskDraft({ ...taskDraft, title: event.target.value })} placeholder="任务名称" /><input type="number" min="1" value={taskDraft.estimatedMinutes} onChange={(event) => setTaskDraft({ ...taskDraft, estimatedMinutes: Number(event.target.value) })} placeholder="预计分钟" /><input type="date" value={taskDraft.dueAt} onChange={(event) => setTaskDraft({ ...taskDraft, dueAt: event.target.value })} /><button className="primary-button" type="button" onClick={() => createTask(stage.id)}><Check size={15} /> 保存</button><button className="icon-button" type="button" onClick={() => setTaskStageId(null)} title="取消"><X size={15} /></button></div> : <button className="text-button task-add-button" type="button" onClick={() => setTaskStageId(stage.id)}><Plus size={14} /> 添加任务</button>}
            </div>
          </section>)}
          {editingStageId === 'new' && <div className="stage-new-form"><input value={stageDraft.title} onChange={(event) => setStageDraft({ ...stageDraft, title: event.target.value })} placeholder="阶段名称" /><input type="date" value={stageDraft.dueLabel} onChange={(event) => setStageDraft({ ...stageDraft, dueLabel: event.target.value })} /><button className="primary-button" type="button" onClick={saveStage}><Check size={15} /> 保存</button></div>}
        </section>

        <section className="next-actions">
          <div className="section-heading"><div><span className="eyebrow">接下来</span><h3>近期安排</h3></div></div>
          {nextItems.map((item) => <article className="next-action-row" key={item.id}><Clock3 size={19} /><div><strong>{item.title}</strong><span>{item.date} · {item.time}{item.taskId ? ' · 已关联任务' : ''}</span></div><span>{item.duration}m</span></article>)}
          {nextItems.length === 0 && <div className="simple-empty">该计划暂时没有近期安排。</div>}
        </section>
      </div>

      {resultTask && <div className="modal-scrim" role="presentation"><section className="editor-modal" role="dialog" aria-modal="true" aria-label="补充完成结果"><div className="modal-heading"><div><span className="eyebrow">任务已完成</span><h2>补充执行结果</h2></div><button className="icon-button" type="button" onClick={() => setResultTask(null)} title="跳过"><X size={18} /></button></div><div className="modal-fields"><label>实际分钟<input type="number" min="1" value={resultDraft.actualMinutes} onChange={(event) => setResultDraft({ ...resultDraft, actualMinutes: event.target.value })} /></label><label>结果备注<textarea value={resultDraft.reason} onChange={(event) => setResultDraft({ ...resultDraft, reason: event.target.value })} rows={3} /></label></div><div className="modal-actions"><button className="secondary-button" type="button" onClick={() => setResultTask(null)}>跳过</button><button className="primary-button" type="button" onClick={() => { onUpdateTask({ ...resultTask, status: 'done', actualMinutes: resultDraft.actualMinutes ? Number(resultDraft.actualMinutes) : undefined, reason: resultDraft.reason }); setResultTask(null) }}><Check size={16} /> 保存结果</button></div></section></div>}
    </div>
  )
}
