import type { CalendarItem, Note, Plan, SourceMaterial, TodoItem } from '../types/planner'

// 这些数据只用于后端不可用时的本地预览，真实业务数据由 plannerApi 加载。

export const plans: Plan[] = [
  {
    id: 'product',
    title: '独立开发产品稳定迭代',
    subtitle: '本月重点 · 还有 114 天',
    progress: 68,
    color: '#d39a24',
    status: 'active',
    completedTasks: 27,
    totalTasks: 40,
    dueDate: '2026-11-24',
    items: [
      { id: 'p1', title: '产品核心功能', progress: 82, dueLabel: '8 月 16 日' },
      { id: 'p2', title: '设计系统整理', progress: 64, dueLabel: '8 月 23 日' },
      { id: 'p3', title: '邀请首批用户', progress: 36, dueLabel: '9 月 5 日' },
    ],
  },
  {
    id: 'travel',
    title: '体验旅居生活',
    subtitle: '长期计划 · 云南篇',
    progress: 42,
    color: '#72806a',
    status: 'active',
    completedTasks: 8,
    totalTasks: 19,
    dueDate: '2026-10-18',
    items: [
      { id: 't1', title: 'DN 余村数字游民生活', progress: 58, dueLabel: '9 月 12 日' },
      { id: 't2', title: '旅居内容记录', progress: 26, dueLabel: '持续进行' },
    ],
  },
  {
    id: 'brand',
    title: '个人品牌长期建设',
    subtitle: '内容与影响力',
    progress: 56,
    color: '#b85f42',
    status: 'active',
    completedTasks: 14,
    totalTasks: 25,
    dueDate: '2026-12-31',
    items: [
      { id: 'b1', title: '稳定更新每周内容', progress: 72, dueLabel: '每周五' },
      { id: 'b2', title: '完成 3 篇深度文章', progress: 33, dueLabel: '8 月 30 日' },
    ],
  },
  {
    id: 'learning',
    title: '持续学习与输入',
    subtitle: 'AI 与产品设计',
    progress: 31,
    color: '#7c647d',
    status: 'active',
    completedTasks: 9,
    totalTasks: 29,
    dueDate: '2026-12-15',
    items: [
      { id: 'l1', title: '完成 Agent 架构课程', progress: 46, dueLabel: '8 月 27 日' },
      { id: 'l2', title: '每周阅读与复盘', progress: 22, dueLabel: '持续进行' },
    ],
  },
]

export const calendarItems: CalendarItem[] = [
  { id: 'e01', date: '2026-08-01', title: '梳理八月产品目标', time: '09:00', planId: 'product', color: '#d39a24', status: 'done', duration: 90 },
  { id: 'e02', date: '2026-08-02', title: '完善首页信息结构', time: '10:00', planId: 'product', color: '#d39a24', status: 'pending', duration: 120 },
  { id: 'e03', date: '2026-08-02', title: '本周计划复盘', time: '20:30', planId: 'learning', color: '#7c647d', status: 'pending', duration: 30 },
  { id: 'e20', date: '2026-08-02', title: '高数极限与连续复习', time: '15:00', planId: 'learning', color: '#7c647d', status: 'pending', duration: 90 },
  { id: 'e04', date: '2026-08-04', title: '整理产品需求优先级', time: '09:30', planId: 'product', color: '#d39a24', status: 'pending', duration: 60 },
  { id: 'e05', date: '2026-08-05', title: 'Agent 路由调研', time: '19:30', planId: 'learning', color: '#7c647d', status: 'pending', duration: 90 },
  { id: 'e06', date: '2026-08-07', title: '发布每周内容', time: '18:00', planId: 'brand', color: '#b85f42', status: 'pending', duration: 45 },
  { id: 'e07', date: '2026-08-10', title: '完成日历交互原型', time: '10:00', planId: 'product', color: '#d39a24', status: 'pending', duration: 120 },
  { id: 'e08', date: '2026-08-11', title: '旅居地点资料整理', time: '15:00', planId: 'travel', color: '#72806a', status: 'pending', duration: 60 },
  { id: 'e09', date: '2026-08-13', title: '首页体验测试', time: '14:00', planId: 'product', color: '#d39a24', status: 'pending', duration: 90 },
  { id: 'e10', date: '2026-08-14', title: '深度文章初稿', time: '19:00', planId: 'brand', color: '#b85f42', status: 'pending', duration: 120 },
  { id: 'e11', date: '2026-08-17', title: '月中计划校准', time: '09:00', planId: 'product', color: '#d39a24', status: 'pending', duration: 45 },
  { id: 'e12', date: '2026-08-18', title: '云南旅居预算表', time: '20:00', planId: 'travel', color: '#72806a', status: 'pending', duration: 60 },
  { id: 'e13', date: '2026-08-20', title: '完成课程第三章', time: '19:30', planId: 'learning', color: '#7c647d', status: 'pending', duration: 90 },
  { id: 'e14', date: '2026-08-21', title: '发布产品进度记录', time: '18:30', planId: 'brand', color: '#b85f42', status: 'pending', duration: 45 },
  { id: 'e15', date: '2026-08-24', title: '邀请测试用户', time: '10:00', planId: 'product', color: '#d39a24', status: 'pending', duration: 60 },
  { id: 'e16', date: '2026-08-25', title: '整理用户反馈', time: '15:00', planId: 'product', color: '#d39a24', status: 'pending', duration: 90 },
  { id: 'e17', date: '2026-08-27', title: 'Agent 课程结课', time: '20:00', planId: 'learning', color: '#7c647d', status: 'pending', duration: 90 },
  { id: 'e18', date: '2026-08-28', title: '深度文章发布', time: '18:00', planId: 'brand', color: '#b85f42', status: 'pending', duration: 60 },
  { id: 'e19', date: '2026-08-30', title: '八月月度复盘', time: '20:30', planId: 'learning', color: '#7c647d', status: 'pending', duration: 45 },
]

export const initialTodos: TodoItem[] = [
  { id: 'td1', title: '给首批测试用户发送邀请', date: '2026-08-02', time: '16:00', priority: '高', done: false, reminder: '提前 30 分钟' },
  { id: 'td2', title: '整理高数第二章错题', date: '2026-08-02', time: '21:00', priority: '中', done: false, reminder: '到点提醒' },
  { id: 'td3', title: '续费产品域名', date: '2026-08-03', time: '10:00', priority: '高', done: false, reminder: '提前 1 天' },
  { id: 'td4', title: '预约本月体检', date: '2026-08-05', time: '09:30', priority: '低', done: false, reminder: '提前 2 小时' },
  { id: 'td5', title: '购买新的速写本', date: '2026-08-01', time: '18:20', priority: '低', done: true, reminder: '无提醒' },
]

export const dailyCompletion = Array.from({ length: 31 }, (_, index) => {
  const day = index + 1
  const planned = 3 + ((day * 7) % 5)
  const completed = Math.max(0, planned - ((day * 3) % 4))
  return {
    day: String(day),
    planned,
    completed: day > 18 ? Math.max(0, completed - 1) : completed,
    minutes: 35 + ((day * 47) % 180),
  }
})

export const monthlyHistory = [
  { month: '3 月', completion: 54, completed: 48, delayed: 16 },
  { month: '4 月', completion: 61, completed: 55, delayed: 13 },
  { month: '5 月', completion: 67, completed: 63, delayed: 11 },
  { month: '6 月', completion: 72, completed: 69, delayed: 9 },
  { month: '7 月', completion: 76, completed: 74, delayed: 8 },
  { month: '8 月', completion: 81, completed: 82, delayed: 6 },
]

export const heatmapValues = Array.from({ length: 119 }, (_, index) => {
  const value = (index * 11 + index * index) % 6
  const completed = index % 13 === 0 ? 0 : value
  const planned = completed + ((index * 5) % 3)
  return {
    id: index,
    value: completed,
    planned,
    completed,
    pending: planned - completed,
    label: '第 ' + (index + 1) + ' 天',
  }
})

export const notes: Note[] = [
  {
    id: 'n1',
    title: '极限的直观理解',
    category: '学习笔记',
    excerpt: '极限描述的是函数值无限接近某个数的趋势，关键不在于取到，而在于逼近。',
    updatedAt: '今天 14:26',
    color: '#7c647d',
    relatedIds: ['n2', 'n4'],
    source: '个人整理',
  },
  {
    id: 'n2',
    title: '连续性的三个条件',
    category: '学习笔记',
    excerpt: '函数在一点连续，需要函数值存在、极限存在，并且两者相等。',
    updatedAt: '昨天 21:10',
    color: '#7c647d',
    relatedIds: ['n1', 'n3'],
    source: '个人整理',
  },
  {
    id: 'n3',
    title: '如何把大目标拆成可执行任务',
    category: '计划方法',
    excerpt: '先写清楚验收标准，再估算一次行动的最小时间块，最后放进真实可用的日历空档。',
    updatedAt: '7 月 31 日',
    color: '#d39a24',
    relatedIds: ['n5', 'n6'],
    source: '个人整理',
  },
  {
    id: 'n4',
    title: '高数错题：等价无穷小',
    category: '学习笔记',
    excerpt: '使用等价无穷小前，先确认替换位置和适用条件，不能在加减结构中直接替换。',
    updatedAt: '7 月 29 日',
    color: '#7c647d',
    relatedIds: ['n1', 'n2'],
    source: '个人整理',
  },
  {
    id: 'n5',
    title: '周计划的缓冲时间',
    category: '计划方法',
    excerpt: '每周至少保留一段没有预先填满的时间，用来承接临时事项和延期任务。',
    updatedAt: '7 月 27 日',
    color: '#d39a24',
    relatedIds: ['n3', 'n6'],
    source: '复盘沉淀',
  },
  {
    id: 'n6',
    title: '产品首页信息层级',
    category: '产品设计',
    excerpt: '首页先回答现在要做什么，再回答长期目标如何推进，细节放到点击后的上下文页面。',
    updatedAt: '7 月 25 日',
    color: '#b85f42',
    relatedIds: ['n3', 'n5'],
    source: '个人整理',
  },
  {
    id: 'n7',
    title: '小红书：高数复习路径',
    category: '灵感收藏',
    excerpt: '从极限、导数和积分的概念链开始复习，再按题型整理常见错误。',
    updatedAt: '7 月 23 日',
    color: '#b85f42',
    relatedIds: ['n1', 'n4'],
    source: '小红书',
  },
  {
    id: 'n8',
    title: '一次复盘应该留下什么',
    category: '复盘记录',
    excerpt: '留下事实、偏差、原因和下一步动作，不把情绪描述替代成执行结论。',
    updatedAt: '7 月 21 日',
    color: '#72806a',
    relatedIds: ['n3', 'n5'],
    source: 'AI 复盘',
  },
]

export const sourceMaterials: SourceMaterial[] = [
  {
    id: 'm1',
    source: '哔哩哔哩',
    title: '高数入门：极限与连续，一次讲清楚',
    summary: '从图像和数列逼近开始建立极限直觉，适合复习前先快速观看。',
    meta: '18:42 · 课程精选',
    url: 'https://search.bilibili.com/all?keyword=%E9%AB%98%E7%AD%89%E6%95%B0%E5%AD%A6%20%E6%9E%81%E9%99%90%20%E8%BF%9E%E7%BB%AD',
    color: '#b85f42',
  },
  {
    id: 'm2',
    source: '小红书',
    title: '高数极限复习思维导图',
    summary: '把极限的定义、常用等价无穷小和典型题型整理成一张复习路径。',
    meta: '收藏笔记 · 8 月 1 日',
    url: 'https://www.xiaohongshu.com/search_result?keyword=%E9%AB%98%E6%95%B0%20%E6%9E%81%E9%99%90%20%E6%80%9D%E7%BB%B4%E5%AF%BC%E5%9B%BE',
    color: '#d77d55',
  },
  {
    id: 'm3',
    source: '网页',
    title: '连续函数与间断点 · 开放课程资料',
    summary: '用定义和反例区分可去间断、跳跃间断与无穷间断，适合作为课后查阅。',
    meta: '公开资料 · 约 12 分钟',
    url: 'https://zh.wikipedia.org/wiki/%E8%BF%9E%E7%BB%AD%E5%87%BD%E6%95%B0',
    color: '#72806a',
  },
]

export const eventMaterials: Record<string, string[]> = {
  e20: ['m1', 'm2', 'm3'],
  e05: ['m3'],
}
