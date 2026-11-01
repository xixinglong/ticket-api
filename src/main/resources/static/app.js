'use strict';
const $ = selector => document.querySelector(selector);
const state = {user: null, page: 1, size: 10, busy: false, submit: null, generation: 0};
const statusNames = {OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已完成', CANCELLED: '已取消'};
const roleNames = {USER: '普通用户', AGENT: '客服', ADMIN: '管理员'};
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const isAdmin = () => state.user?.roles.includes('ADMIN');
function notice(message = '') { $('#notice').textContent = message; $('#notice').hidden = !message; }
function signedOut() {
  state.user = null; state.generation++; $('#workspace').hidden = true; $('#login-view').hidden = false;
  $('#identity').replaceChildren(); $('#editor').close(); $('#table-body').replaceChildren();
}
async function api(path, {method = 'GET', body, form = false} = {}) {
  const headers = {'Accept': 'application/json'};
  if (method !== 'GET') {
    const token = await api('/api/csrf'); headers[token.headerName] = token.token;
    headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json';
  }
  const response = await fetch(path, {method, credentials:'same-origin', cache:'no-store', headers,
    body: body === undefined ? undefined : form ? new URLSearchParams(body) : JSON.stringify(body)});
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { throw new Error('服务响应异常，请稍后重试'); }
  if (!response.ok) {
    if (response.status === 401 && state.user) signedOut();
    throw new Error((data?.message || '请求失败') + (data?.requestId ? `（请求编号 ${data.requestId}）` : ''));
  }
  return data;
}
async function load() {
  if (!state.user) return;
  const generation = ++state.generation;
  const admin = isAdmin();
  $('#refresh').disabled = true;
  try {
    const data = await api(`${admin ? '/api/admin/users' : '/api/tickets'}?page=${state.page}&size=${state.size}`);
    if (generation !== state.generation) return;
    if (!data.items.length && data.total && state.page > 1) { state.page--; return load(); }
    $('#table-head').innerHTML = admin ? '<tr><th>编号</th><th>用户名</th><th>角色</th><th>状态</th><th>操作</th></tr>' : '<tr><th>编号</th><th>标题</th><th>状态</th><th>操作</th></tr>';
    $('#table-body').innerHTML = data.items.map(item => admin
      ? `<tr><td>#${escapeHtml(item.id)}</td><td>${escapeHtml(item.username)}</td><td>${roleNames[item.role] || escapeHtml(item.role)}</td><td><span class="badge ${item.enabled ? 'OPEN' : ''}">${item.enabled ? '启用' : '禁用'}</span></td><td>${item.role === 'ADMIN' ? '—' : `<button class="link" data-action="toggle" data-id="${escapeHtml(item.id)}" data-enabled="${!item.enabled}">${item.enabled ? '禁用' : '启用'}</button> · <button class="link" data-action="reset" data-id="${escapeHtml(item.id)}">重置密码</button>`}</td></tr>`
      : `<tr><td>#${escapeHtml(item.id)}</td><td><button class="link" data-action="detail" data-id="${escapeHtml(item.id)}">${escapeHtml(item.title)}</button></td><td><span class="badge ${escapeHtml(item.status)}">${statusNames[item.status] || escapeHtml(item.status)}</span></td><td><button class="link" data-action="detail" data-id="${escapeHtml(item.id)}">查看详情</button></td></tr>`).join('');
    $('#empty').hidden = data.items.length !== 0;
    $('#total').textContent = `共 ${data.total} 条记录`;
    $('#page').textContent = `${state.page} / ${Math.max(1, Math.ceil(data.total / state.size))}`;
    $('#previous').disabled = state.page <= 1; $('#next').disabled = state.page * state.size >= data.total;
  } catch (error) { notice(error.message); } finally { $('#refresh').disabled = false; }
}
async function enter() {
  state.user = await api('/api/me'); state.page = 1;
  $('#login-view').hidden = true; $('#workspace').hidden = false;
  $('#identity').innerHTML = `<span>${escapeHtml(state.user.username)} · ${roleNames[state.user.roles[0]] || ''}</span><button id="logout">退出</button>`;
  $('#logout').onclick = async () => { try { await api('/api/logout', {method:'POST'}); signedOut(); notice(); } catch (e) { notice(e.message); } };
  $('#workspace-title').textContent = isAdmin() ? '用户管理' : '工单列表';
  $('#workspace-role').textContent = isAdmin() ? '账号与访问权限' : state.user.roles.includes('AGENT') ? '客服工作台' : '我的工作台';
  $('#create').textContent = isAdmin() ? '创建用户' : '新建工单';
  $('#create').hidden = state.user.roles.includes('AGENT');
  await load();
}
function dialog(title, fields, submit, label = '保存') {
  $('#dialog-title').textContent = title; $('#fields').innerHTML = fields;
  $('#dialog-error').hidden = true; $('#save').hidden = !submit; $('#save').textContent = label;
  state.submit = submit; $('#editor').showModal();
}
$('#login-form').onsubmit = async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; notice();
  try { await api('/api/login', {method:'POST', form:true, body:Object.fromEntries(new FormData(event.target))}); event.target.reset(); await enter(); }
  catch (error) { notice(error.message); } finally { button.disabled = false; }
};
$('#editor-form').onsubmit = async event => {
  event.preventDefault(); if (state.busy || !state.submit) return;
  state.busy = true; $('#save').disabled = true; $('#cancel-dialog').disabled = true; $('#close-dialog').disabled = true;
  try { await state.submit(Object.fromEntries(new FormData(event.target))); $('#editor').close(); notice(); await load(); }
  catch (error) { if ($('#editor').open) { $('#dialog-error').textContent = error.message; $('#dialog-error').hidden = false; } else notice(error.message); }
  finally { state.busy = false; $('#save').disabled = false; $('#cancel-dialog').disabled = false; $('#close-dialog').disabled = false; }
};
$('#editor').addEventListener('cancel', event => { if (state.busy) event.preventDefault(); });
$('#editor').addEventListener('close', () => { $('#fields').replaceChildren(); state.submit = null; });
$('#close-dialog').onclick = $('#cancel-dialog').onclick = () => $('#editor').close();
$('#refresh').onclick = () => { notice(); load(); };
$('#previous').onclick = () => { state.page--; load(); };
$('#next').onclick = () => { state.page++; load(); };
$('#size').onchange = event => { state.size = Number(event.target.value); state.page = 1; load(); };
$('#create').onclick = () => {
  if (isAdmin()) dialog('创建用户', '<label>用户名<input name="username" required minlength="3" maxlength="64" pattern="[A-Za-z0-9][A-Za-z0-9_.-]{2,63}" autocomplete="off"></label><label>初始密码<input name="password" type="password" required minlength="12" maxlength="72" autocomplete="new-password"></label><label>角色<select name="role"><option value="USER">普通用户</option><option value="AGENT">客服</option></select></label>', body => api('/api/admin/users', {method:'POST', body}), '创建');
  else dialog('新建工单', '<label>标题<input name="title" required maxlength="100"></label><label>描述<textarea name="description" maxlength="10000"></textarea></label>', body => api('/api/tickets', {method:'POST', body}), '创建');
};
$('#table-body').onclick = async event => {
  const button = event.target.closest('button[data-action]'); if (!button || state.busy) return;
  const {id, action, enabled} = button.dataset; notice();
  if (action === 'reset') return dialog(`重置密码 · #${id}`, '<label>新密码<input name="password" type="password" required minlength="12" maxlength="72" autocomplete="new-password"></label>', body => api(`/api/admin/users/${id}/reset-password`, {method:'POST',body}), '重置密码');
  if (action === 'toggle') return dialog(`${enabled === 'true' ? '启用' : '禁用'}用户 · #${id}`, '<p>确认更改该账号的启用状态？</p>', () => api(`/api/admin/users/${id}/enabled`, {method:'PATCH',body:{enabled:enabled === 'true'}}), '确认');
  button.disabled = true;
  try {
    const ticket = await api(`/api/tickets/${id}`);
    if (!state.user) return;
    const agent = state.user.roles.includes('AGENT');
    const operation = ticket.status === 'OPEN' ? (agent ? 'claim' : 'cancel') : agent && ticket.status === 'IN_PROGRESS' ? 'resolve' : null;
    const labels = {claim:'接单',cancel:'取消工单',resolve:'完成工单'};
    dialog(ticket.title, `<div class="detail-meta">#${escapeHtml(id)} · ${statusNames[ticket.status]}</div><p>${escapeHtml(ticket.description || '暂无描述')}</p>`, operation ? () => api(`/api/tickets/${id}/${operation}`, {method:'POST'}) : null, labels[operation]);
  } catch (error) { notice(error.message); } finally { button.disabled = false; }
};
enter().catch(error => { signedOut(); if (!error.message.startsWith('请先登录')) notice(error.message); });
