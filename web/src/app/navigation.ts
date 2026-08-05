import { BarChart3, BrainCircuit, CalendarDays, CheckSquare2, GraduationCap, ListChecks, MessageSquareText, NotebookPen, Trash2 } from 'lucide-react'

/** 顶层页面状态集中定义，避免布局组件和 App 各自维护一套字符串。 */
export type View = 'calendar' | 'plans' | 'todos' | 'notes' | 'stats' | 'agent' | 'review' | 'trash' | 'schedule' | 'learning'
export type NavView = Exclude<View, 'schedule'>
export type GlobalTargetType = 'plan' | 'todo' | 'note' | 'schedule'

export interface GlobalSearchItem {
  id: string
  type: GlobalTargetType
  title: string
  meta: string
}

export interface NotificationEntry extends GlobalSearchItem {
  time?: string
}

/** 导航配置是唯一事实源，侧栏只负责渲染和切换。 */
export const navItems: Array<{ id: NavView; label: string; icon: typeof CalendarDays }> = [
  { id: 'calendar', label: '日历', icon: CalendarDays },
  { id: 'plans', label: '计划', icon: ListChecks },
  { id: 'todos', label: '待办', icon: CheckSquare2 },
  { id: 'notes', label: '笔记', icon: NotebookPen },
  { id: 'stats', label: '计划统计', icon: BarChart3 },
  { id: 'learning', label: '学习目标', icon: GraduationCap },
  { id: 'agent', label: 'AI 对话', icon: MessageSquareText },
  { id: 'review', label: 'AI 复盘', icon: BrainCircuit },
  { id: 'trash', label: '回收站', icon: Trash2 },
]
