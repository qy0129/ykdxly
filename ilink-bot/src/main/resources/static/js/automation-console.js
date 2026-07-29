const token = location.pathname.split('/').filter(Boolean).pop();
const base = `/api/automation/${token}/tasks`;
const tasksNode = document.querySelector('#tasks');
const detailNode = document.querySelector('#detail');
let selected = '';

async function loadTasks() {
  const tasks = await fetch(base, {cache: 'no-store'}).then(r => r.json());
  tasksNode.innerHTML = tasks.map(task => `<button class="task" data-id="${escapeHtml(task.id)}"><strong>${escapeHtml(task.goal)}</strong><span>${task.id} · ${task.status} · ${formatTime(task.updatedAt)}</span></button>`).join('') || '<p class="empty">暂无任务</p>';
  document.querySelectorAll('.task').forEach(button => button.onclick = () => loadDetail(button.dataset.id));
  if (selected) loadDetail(selected);
}

async function loadDetail(id) {
  selected = id;
  const data = await fetch(`${base}/${id}`, {cache: 'no-store'}).then(r => r.json());
  if (data.error) { detailNode.textContent = data.error; return; }
  detailNode.innerHTML = `<div class="summary"><h2>${escapeHtml(data.task.goal)}</h2><div class="meta">${data.task.id} · ${data.task.status} · 第 ${data.task.currentStep} 步</div><div class="actions"><button data-action="approve">批准</button><button data-action="reject" class="danger">拒绝</button><button data-action="cancel" class="danger">取消</button><button data-action="retry">重试</button></div></div><h3>步骤</h3>${data.steps.map(step => `<div class="row"><strong>${step.sequence}. ${escapeHtml(step.title)} · ${step.status}</strong>${step.output ? `<pre>${escapeHtml(step.output)}</pre>` : ''}${step.error ? `<pre>${escapeHtml(step.error)}</pre>` : ''}</div>`).join('')}<h3>日志</h3>${data.logs.map(log => `<div class="row"><strong>${escapeHtml(log.event)} · ${escapeHtml(log.status)}</strong><div class="meta">${formatTime(log.time)}</div><pre>${escapeHtml(log.message)}</pre></div>`).join('')}`;
  document.querySelectorAll('[data-action]').forEach(button => button.onclick = () => runAction(id, button.dataset.action));
}

async function runAction(id, action) {
  const result = await fetch(`${base}/${id}/${action}`, {method: 'POST'}).then(r => r.json());
  if (result.message) window.alert(result.message);
  await loadTasks();
}

function escapeHtml(value) { return String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function formatTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : ''; }
document.querySelector('#refresh').onclick = loadTasks;
loadTasks();
