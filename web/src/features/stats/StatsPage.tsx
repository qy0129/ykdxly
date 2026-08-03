import { useState } from 'react'
import { Download, FileText, RefreshCw, TrendingUp } from 'lucide-react'
import { Bar, BarChart, CartesianGrid, Cell, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { plannerApi, type PlannerStats } from '../../services/plannerApi'

/** 统计功能只消费标准统计模型，导出通过统一 API 服务完成。 */
export function Heatmap({ values }: { values: PlannerStats['heatmap'] }) {
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

export function StatsTooltip({ active, payload, label }: { active?: boolean; payload?: Array<{ value: number; name: string; color: string }>; label?: string }) {
  if (!active || !payload?.length) return null
  return (
    <div className="chart-tooltip">
      <strong>{label}</strong>
      {payload.map((entry) => <span key={entry.name}><i style={{ backgroundColor: entry.color }} />{entry.name}：{entry.value}</span>)}
    </div>
  )
}

export function StatsPage({ stats }: { stats: PlannerStats }) {
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

