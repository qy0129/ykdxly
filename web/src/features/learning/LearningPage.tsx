import { useEffect, useState } from 'react'
import { GraduationCap, RefreshCw } from 'lucide-react'
import { plannerApi, type LearningGoal } from '../../services/plannerApi'

const statusLabel: Record<string, string> = {
  active: '进行中',
  paused: '已暂停',
  completed: '已完成',
  abandoned: '已放弃',
}

const priorityLabel: Record<string, string> = {
  high: '高',
  medium: '中',
  low: '低',
}

export function LearningPage() {
  const [goals, setGoals] = useState<LearningGoal[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = async () => {
    setBusy(true)
    setError('')
    try {
      setGoals(await plannerApi.loadLearningGoals())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法加载学习目标')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => { void load() }, [])

  return (
    <div className={`learning-page content-page${busy ? ' is-refreshing' : ''}`}>
      <section className="learning-header">
        <div>
          <span className="eyebrow">学习规划</span>
          <h2>学习目标</h2>
          <p>通过 AI 对话创建和调整，在这里查看进度</p>
        </div>
        <button className="secondary-button" type="button" disabled={busy} onClick={() => void load()}>
          <RefreshCw className={busy ? 'spin' : ''} size={16} /> {busy ? '刷新中' : '刷新'}
        </button>
      </section>

      {error && <p className="ai-chat-error" role="alert">{error}</p>}

      {!goals.length && !busy && (
        <div className="learning-empty">
          <GraduationCap size={40} />
          <p>还没有学习目标</p>
          <p>去「AI 对话」里说一句“创建一个学习目标：……”</p>
        </div>
      )}

      <section className="learning-grid">
        {goals.map((goal) => (
          <article className="learning-card" key={goal.id}>
            <div className="learning-card-head">
              <strong>{goal.title}</strong>
              <span className={`learning-status learning-status-${goal.status}`}>{statusLabel[goal.status] ?? goal.status}</span>
            </div>
            <p className="learning-domain">{goal.domain || '未分类领域'}</p>
            <dl className="learning-meta">
              {goal.targetDate ? <div><dt>目标日期</dt><dd>{goal.targetDate}</dd></div> : null}
              {goal.weeklyHours != null ? <div><dt>每周时长</dt><dd>{goal.weeklyHours} 小时</dd></div> : null}
              <div><dt>优先级</dt><dd>{priorityLabel[goal.priority ?? 'medium'] ?? goal.priority}</dd></div>
            </dl>
            <div className="learning-progress">
              <div className="learning-progress-track">
                <div className="learning-progress-bar" style={{ width: `${Math.min(100, Math.round(goal.progress))}%` }} />
              </div>
              <span>{Math.round(goal.progress)}%</span>
            </div>
          </article>
        ))}
      </section>
    </div>
  )
}
