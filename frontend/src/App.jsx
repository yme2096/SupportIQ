import { useEffect, useState } from 'react';
import { Activity, Bot, BookOpen, Building2, Check, Copy, Headphones, Inbox, LogOut, MessageCircle, Send, Settings, ShieldCheck, Ticket, Upload, Users, ShoppingBag, BarChart3, CreditCard } from 'lucide-react';
import { api } from './api.js';
import { useAuth } from './auth.js';
import KnowledgeBase from './KnowledgeBase.jsx';
import AgentWorkspace from './AgentWorkspace.jsx';
import OrdersWorkspace from './OrdersWorkspace.jsx';
import BillingWorkspace from './BillingWorkspace.jsx';
import AnalyticsDashboard from './AnalyticsDashboard.jsx';
import './phase6.css';

export default function App() {
  const { user, signOut } = useAuth();
  const [tab, setTab] = useState(() => ['overview', 'chat', 'inbox', 'orders', 'analytics', 'billing', 'knowledge', 'team', 'settings', 'account'].includes(new URLSearchParams(window.location.search).get('tab')) ? new URLSearchParams(window.location.search).get('tab') : 'overview');
  const [messages, setMessages] = useState([{ sender_type: 'AI', content: 'Welcome to your company support workspace. Ask about approved policies, returns, shipping or warranties.' }]);
  const [conversationId, setConversationId] = useState(() => sessionStorage.getItem('supportiq-conversation'));
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [health, setHealth] = useState(false);
  const [tickets, setTickets] = useState([]);
  const [stats, setStats] = useState({});
  const [selected, setSelected] = useState(null);
  const [thread, setThread] = useState([]);
  const [reply, setReply] = useState('');
  const [filter, setFilter] = useState('All');
  const [members, setMembers] = useState([]);
  const [profile, setProfile] = useState(null);
  const [settings, setSettings] = useState({});
  const [aiStatus, setAiStatus] = useState(null);
  const [supportSettings, setSupportSettings] = useState({});
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteUrl, setInviteUrl] = useState('');
  const [saved, setSaved] = useState(false);
  const [passwordForm, setPasswordForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const isAdmin = user?.role === 'COMPANY_ADMIN';
  const widget = { name: '', logoUrl: '', welcomeMessage: 'Hello! How can we help?', primaryColor: '#6859cf', ...(settings.widget || {}) };
  const ai = { enabled: true, provider: 'GEMINI', model: 'gemini-3.8-flash', language: 'English', tone: 'Friendly and concise', maxChunks: 4, relevanceThreshold: 0.42, handoffOnUnknown: true, fallbackResponse: 'I could not find that information in the approved company documents. I can connect you with a support agent.', ...(settings.ai || {}) };
  const supportConfig = { firstResponseTargetMinutes: 15, resolutionTargetMinutes: 1440, assignmentStrategy: 'ROUND_ROBIN', businessHours: { timezone: 'Asia/Kolkata', weekdays: [1,2,3,4,5], start: '09:00', end: '18:00' }, priorityTargets: { URGENT: 5, HIGH: 15, NORMAL: 60, LOW: 240 }, ...supportSettings, businessHours: { timezone: 'Asia/Kolkata', weekdays: [1,2,3,4,5], start: '09:00', end: '18:00', ...(supportSettings.businessHours || {}) }, priorityTargets: { URGENT: 5, HIGH: 15, NORMAL: 60, LOW: 240, ...(supportSettings.priorityTargets || {}) } };
  const widgetScriptUrl = `${window.location.origin}/supportiq-widget.js`;
  const widgetApiUrl = import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://localhost:8080' : window.location.origin);
  const widgetSnippet = `<script src="${widgetScriptUrl}" data-company="${profile?.public_id || 'YOUR_COMPANY_ID'}" data-api-base="${widgetApiUrl}" async></script>`;

  async function refresh() {
    try {
      setHealth((await api('/api/v1/health')).status === 'UP');
      const [analytics, queue] = await Promise.all([isAdmin ? api('/api/v1/analytics') : Promise.resolve({}), api('/api/v1/agent/tickets')]);
      setStats(analytics); setTickets(queue);
    } catch (e) { setHealth(false); setError(e.message); }
  }
  useEffect(() => { refresh(); }, []);
  useEffect(() => {
    if (!conversationId) return;
    let active = true;
    const load = () => api(`/api/v1/chat/${conversationId}/messages`).then(rows => { if (active && rows.length) setMessages(rows); }).catch(() => {
      if (active) { sessionStorage.removeItem('supportiq-conversation'); setConversationId(null); }
    });
    load(); const timer = setInterval(load, 3000);
    return () => { active = false; clearInterval(timer); };
  }, [conversationId]);
  useEffect(() => {
    if (tab === 'team' && isAdmin) api('/api/users').then(setMembers).catch(e => setError(e.message));
    if ((tab === 'settings' || tab === 'team') && isAdmin) {
      api('/api/company/profile').then(setProfile).catch(e => setError(e.message));
      api('/api/company/settings').then(d => setSettings(d.settings || {})).catch(e => setError(e.message));
      api('/api/company/ai-status').then(setAiStatus).catch(e => setError(e.message));
      api('/api/v1/agent/workspace/support-settings').then(setSupportSettings).catch(e => setError(e.message));
    }
  }, [tab, isAdmin]);

  async function send(message = text) {
    if (!message.trim() || busy) return;
    setMessages(v => [...v, { sender_type: 'CUSTOMER', content: message }]); setText(''); setBusy(true);
    try {
      const data = await api('/api/v1/chat/message', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message, conversationId }) });
      setConversationId(data.conversationId); sessionStorage.setItem('supportiq-conversation', data.conversationId);
      if (data.chatAccessToken) sessionStorage.setItem(`supportiq-chat-token:${data.conversationId}`, data.chatAccessToken);
      setMessages(v => [...v, { sender_type: 'AI', content: data.answer, sources: data.sources, source_refs: data.sourceReferences, retrieval_score: data.relevanceScore, escalated: data.escalated }]); refresh();
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }
  async function openTicket(ticket) { setSelected(ticket); setThread(await api(`/api/v1/chat/${ticket.conversation_id}/messages`)); }
  async function sendReply() {
    if (!reply.trim() || !selected) return;
    await api(`/api/v1/agent/conversations/${selected.conversation_id}/reply`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message: reply }) });
    setReply(''); await openTicket(selected); refresh();
  }
  async function updateTicket(status) {
    await api(`/api/v1/agent/tickets/${selected.id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status }) });
    refresh();
  }
  async function inviteAgent(event) {
    event.preventDefault(); setInviteUrl('');
    try { const result = await api('/api/invitations', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: inviteEmail }) }); setInviteUrl(result.invitationUrl || 'Invitation created. Configure email delivery to share it.'); setInviteEmail(''); }
    catch (e) { setError(e.message); }
  }
  async function saveSettings(event) {
    event.preventDefault();
    try {
      await api('/api/company/profile', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: profile.name, businessEmail: profile.business_email, phone: profile.phone || '', website: profile.website || '' }) });
      await api('/api/company/settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(settings) });
      await api('/api/v1/agent/workspace/support-settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(supportConfig) });
      setSaved(true); setTimeout(() => setSaved(false), 2500);
    } catch (e) { setError(e.message); }
  }
  async function changeRole(member, role) {
    try { await api(`/api/users/${member.id}/role`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role }) }); setMembers(await api('/api/users')); }
    catch (e) { setError(e.message); }
  }
  async function suspend(member) {
    try { await api(`/api/users/${member.id}/status`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: member.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' }) }); setMembers(await api('/api/users')); }
    catch (e) { setError(e.message); }
  }
  async function changePassword(event) {
    event.preventDefault();
    if (passwordForm.newPassword !== passwordForm.confirmPassword) { setError('New passwords do not match.'); return; }
    try { await api('/api/auth/change-password', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(passwordForm) }); setPasswordForm({ currentPassword: '', newPassword: '', confirmPassword: '' }); setSaved(true); setTimeout(() => setSaved(false), 2500); }
    catch (e) { setError(e.message); }
  }

  const title = { overview: 'Overview', chat: 'Customer chat', inbox: 'Agent inbox', orders: 'Orders & refunds', analytics: 'Analytics', billing: 'Billing & usage', knowledge: 'Knowledge base', team: 'Team management', settings: 'Company settings', account: 'Account settings' }[tab];
  return <div className="app">
    <aside>
      <div className="logo"><span><Bot /></span>Support<b>IQ</b></div>
      <div className="workspace"><i>{(user?.companyName || 'S').slice(0, 1)}</i><span><b>{user?.companyName || 'Company workspace'}</b><small>{user?.role?.replace('_', ' ')}</small></span></div>
      <label>WORKSPACE</label>
      {[['Overview', 'overview', Activity], ['Customer chat', 'chat', MessageCircle], ['Agent inbox', 'inbox', Inbox], ['Orders & refunds','orders',ShoppingBag], ...(isAdmin ? [['Analytics','analytics',BarChart3],['Billing & usage','billing',CreditCard],['Knowledge base', 'knowledge', BookOpen], ['Team', 'team', Users], ['Company settings', 'settings', Settings]] : []), ['Account', 'account', Settings]].map(([name, id, Icon]) => <button key={id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}><Icon size={18} />{name}{id === 'inbox' && tickets.length > 0 && <em>{tickets.length}</em>}</button>)}
      <div className="profile"><i>{(user?.fullName || 'U').split(' ').map(s => s[0]).slice(0, 2).join('')}</i><span><b>{user?.fullName}</b><small>{user?.email}</small></span></div>
      <button className="signout" onClick={signOut}><LogOut size={16} /> Sign out</button>
    </aside>
    <main>
      <header><div><small>{(user?.companyName || 'COMPANY').toUpperCase()} / {tab.toUpperCase()}</small><h1>{title}</h1></div><span className={health ? 'online' : 'offline'}>● {health ? 'All systems operational' : 'API unavailable'}</span></header>
      {error && <div className="error" role="alert">{error}<button onClick={() => setError('')}>Dismiss</button></div>}
      {tab === 'chat' && <div className="chatgrid"><section className="chat"><div className="chathead"><i><Bot /></i><span><b>AI support assistant</b><small>● Knowledge-grounded support</small></span><ShieldCheck /></div><div className="messages">{messages.map((m, i) => <div key={m.id || i} className={'msg ' + (m.sender_type === 'CUSTOMER' ? 'right' : '')}><div className="bubble">{m.content}</div>{m.sender_type === 'AGENT' && <small className="source">Human support agent</small>}{m.sources?.length > 0 && <small className="source">Source: {m.sources.join(', ')}</small>}{(m.source_refs||m.sourceReferences)?.length>0&&<small className="source">{(m.source_refs||m.sourceReferences).map(r=>`${r.documentName||r.document_name}${r.pageNumber||r.page_number?` · p. ${r.pageNumber||r.page_number}`:''}${r.section?` · ${r.section}`:''}`).join(' · ')}</small>}{m.retrieval_score!=null&&<small className="source">Retrieval relevance: {Number(m.retrieval_score).toFixed(2)}</small>}{m.escalated && <small className="handoff"><Headphones size={13} /> Escalated to human support · ticket created</small>}</div>)}{busy && <div className="bubble">SupportIQ is thinking…</div>}</div><div className="prompts">{['What is your return policy?', 'How long does delivery take?', 'I need help with a refund.'].map(x => <button key={x} onClick={() => send(x)}>{x}</button>)}</div><div className="composer"><input value={text} onChange={e => setText(e.target.value)} onKeyDown={e => e.key === 'Enter' && send()} placeholder="Ask about your order or company policy…" /><button onClick={() => send()} disabled={busy || !text.trim()}><Send size={17} /></button></div><small className="foot"><ShieldCheck size={12} /> Unknown refund statuses are sent to an agent.</small></section><aside className="rightinfo"><div className="card"><b>Conversation details</b><p>Status <strong>{messages.some(m => m.conversation_status === 'WAITING_FOR_AGENT') ? 'Waiting for agent' : messages.some(m => m.conversation_status === 'AGENT_ACTIVE') ? 'Agent responding' : 'AI handling'}</strong></p><p>Workspace <strong>{user?.companyName}</strong></p></div><div className="card help"><Headphones /><b>Need a human?</b><p>Our support team can take over whenever you need help.</p><button onClick={() => send('I want to speak to a human agent')}>Talk to support</button></div></aside></div>}
      {tab === 'overview' && <><div className="hero"><div><small>✦ CUSTOMER SUPPORT INTELLIGENCE</small><h2>Support that feels<br /><span>effortlessly human.</span></h2><p>Trusted AI answers, faster resolutions and thoughtful human support in one workspace.</p><button onClick={() => setTab('chat')}>Try customer chat →</button></div><div className="heroart"><Bot size={100} /></div></div><h3>Workspace overview</h3><div className="stats">{[['Conversations', stats.conversations], ['Open tickets', stats.pendingTickets], ['Resolved', stats.resolvedTickets], ['Knowledge sources', stats.knowledgeDocuments]].map(([name, value]) => <div className="stat" key={name}><small>{name}</small><b>{value ?? 0}</b></div>)}</div><section className="card"><h3>Recent support requests</h3>{tickets.slice(0, 5).map(t => <p className="ticketrow" key={t.id}><Ticket size={15} /><b>{t.title}</b><span>{t.status}</span></p>)}</section></>}
      {tab === 'inbox' && <AgentWorkspace user={user} onActivity={refresh} />}
      {tab === 'orders' && <OrdersWorkspace user={user} />}
      {tab === 'analytics' && isAdmin && <AnalyticsDashboard />}
      {tab === 'billing' && isAdmin && <BillingWorkspace />}
      {tab === 'knowledge' && isAdmin && <KnowledgeBase />}
      {tab === 'team' && isAdmin && <div className="card team"><h2><Users size={20} /> Company team</h2><p>Invite agents with a single-use link that expires in seven days.</p><form className="invite-form" onSubmit={inviteAgent}><input type="email" required value={inviteEmail} onChange={e => setInviteEmail(e.target.value)} placeholder="agent@company.com" /><button type="submit">Invite agent</button></form>{inviteUrl && <div className="invite-link"><span>{inviteUrl}</span><button onClick={() => navigator.clipboard.writeText(inviteUrl)} aria-label="Copy invitation link"><Copy size={15} /></button></div>}<div className="team-list">{members.map(m => <div key={m.id} className="team-member"><div><b>{m.full_name}</b><small>{m.email}</small></div><select value={m.role} disabled={m.id === user.id} onChange={e => changeRole(m, e.target.value)}><option value="COMPANY_ADMIN">Company admin</option><option value="AGENT">Agent</option></select><span className={m.status === 'ACTIVE' ? 'member-active' : 'member-suspended'}>{m.status}</span><button className="secondary" disabled={m.id === user.id} onClick={() => suspend(m)}>{m.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'}</button></div>)}</div></div>}
      {tab === 'settings' && isAdmin && profile && <form className="card company-settings" onSubmit={saveSettings}><h2><Building2 size={20} /> Company profile</h2><label>Company name<input required maxLength="160" value={profile.name || ''} onChange={e => setProfile({ ...profile, name: e.target.value })} /></label><label>Business email<input required type="email" value={profile.business_email || ''} onChange={e => setProfile({ ...profile, business_email: e.target.value })} /></label><label>Phone<input value={profile.phone || ''} onChange={e => setProfile({ ...profile, phone: e.target.value })} /></label><label>Website<input value={profile.website || ''} onChange={e => setProfile({ ...profile, website: e.target.value })} /></label><h3>Workspace preferences</h3><label>Workspace name<input value={settings.workspaceName || ''} onChange={e => setSettings({ ...settings, workspaceName: e.target.value })} /></label><label>Timezone<input value={settings.timezone || ''} onChange={e => setSettings({ ...settings, timezone: e.target.value })} /></label><h3>Support coverage and response targets</h3><div className="settings-row"><label>First response target (minutes)<input type="number" min="1" max="10080" value={supportConfig.firstResponseTargetMinutes} onChange={e=>setSupportSettings({...supportConfig,firstResponseTargetMinutes:Number(e.target.value)})}/></label><label>Resolution target (minutes)<input type="number" min="1" max="43200" value={supportConfig.resolutionTargetMinutes} onChange={e=>setSupportSettings({...supportConfig,resolutionTargetMinutes:Number(e.target.value)})}/></label></div><label>Automatic assignment strategy<select value={supportConfig.assignmentStrategy} onChange={e=>setSupportSettings({...supportConfig,assignmentStrategy:e.target.value})}><option value="ROUND_ROBIN">Round robin</option><option value="LEAST_LOADED">Least loaded</option></select></label><div className="settings-row"><label>Business-hours timezone<input value={supportConfig.businessHours.timezone} onChange={e=>setSupportSettings({...supportConfig,businessHours:{...supportConfig.businessHours,timezone:e.target.value}})}/></label><label>Business days (ISO weekday numbers)<input value={supportConfig.businessHours.weekdays.join(',')} onChange={e=>setSupportSettings({...supportConfig,businessHours:{...supportConfig.businessHours,weekdays:e.target.value.split(',').map(v=>Number(v.trim())).filter(v=>v>=1&&v<=7)}})}/></label></div><div className="settings-row"><label>Business day starts<input type="time" value={supportConfig.businessHours.start} onChange={e=>setSupportSettings({...supportConfig,businessHours:{...supportConfig.businessHours,start:e.target.value}})}/></label><label>Business day ends<input type="time" value={supportConfig.businessHours.end} onChange={e=>setSupportSettings({...supportConfig,businessHours:{...supportConfig.businessHours,end:e.target.value}})}/></label></div><div className="settings-row"><label>Urgent first response (minutes)<input type="number" min="1" value={supportConfig.priorityTargets.URGENT} onChange={e=>setSupportSettings({...supportConfig,priorityTargets:{...supportConfig.priorityTargets,URGENT:Number(e.target.value)}})}/></label><label>High first response (minutes)<input type="number" min="1" value={supportConfig.priorityTargets.HIGH} onChange={e=>setSupportSettings({...supportConfig,priorityTargets:{...supportConfig.priorityTargets,HIGH:Number(e.target.value)}})}/></label></div><div className="settings-row"><label>Normal first response (minutes)<input type="number" min="1" value={supportConfig.priorityTargets.NORMAL} onChange={e=>setSupportSettings({...supportConfig,priorityTargets:{...supportConfig.priorityTargets,NORMAL:Number(e.target.value)}})}/></label><label>Low first response (minutes)<input type="number" min="1" value={supportConfig.priorityTargets.LOW} onChange={e=>setSupportSettings({...supportConfig,priorityTargets:{...supportConfig.priorityTargets,LOW:Number(e.target.value)}})}/></label></div><h3>AI knowledge assistant</h3><label className="settings-check"><input type="checkbox" checked={ai.enabled} onChange={e => setSettings({ ...settings, ai: { ...ai, enabled: e.target.checked } })} /> Enable AI answers</label><label>AI provider<select value={ai.provider} onChange={e => setSettings({ ...settings, ai: { ...ai, provider: e.target.value } })}><option value="GEMINI">Gemini {aiStatus?.answerConfigured ? '(server configured)' : '(local fallback active)'}</option><option value="LOCAL_FALLBACK">Knowledge-only fallback</option></select></label><label>Gemini model<input value={ai.model} onChange={e => setSettings({ ...settings, ai: { ...ai, model: e.target.value } })} /></label><div className="settings-row"><label>Response language<input maxLength="40" value={ai.language} onChange={e => setSettings({ ...settings, ai: { ...ai, language: e.target.value } })} /></label><label>Response tone<input maxLength="80" value={ai.tone} onChange={e => setSettings({ ...settings, ai: { ...ai, tone: e.target.value } })} /></label></div><div className="settings-row"><label>Maximum source sections<input type="number" min="1" max="8" value={ai.maxChunks} onChange={e => setSettings({ ...settings, ai: { ...ai, maxChunks: Number(e.target.value) } })} /></label><label>Minimum relevance<input type="number" min="0" max="1" step="0.05" value={ai.relevanceThreshold} onChange={e => setSettings({ ...settings, ai: { ...ai, relevanceThreshold: Number(e.target.value) } })} /></label></div><label className="settings-check"><input type="checkbox" checked={ai.handoffOnUnknown} onChange={e => setSettings({ ...settings, ai: { ...ai, handoffOnUnknown: e.target.checked } })} /> Create a support ticket when the answer is uncertain</label><label>Fallback response<textarea maxLength="500" rows="3" value={ai.fallbackResponse} onChange={e => setSettings({ ...settings, ai: { ...ai, fallbackResponse: e.target.value } })} /></label><p className="widget-intro">Server AI status: {aiStatus ? `Answer generation: ${aiStatus.answerConfigured ? 'Gemini configured' : 'Gemini key missing; safe fallback active'}. Embeddings: ${aiStatus.embeddingProvider} (${aiStatus.embeddingModel}).` : 'Checking server AI configuration…'} Provider credentials are managed server-side and are not stored in workspace settings.</p><h3 className="widget-section-title">Website chat widget</h3><p className="widget-intro">Customize the assistant, preview it, then install the company-specific snippet on your site.</p><label>Widget name<input maxLength="40" placeholder={profile.name} value={widget.name} onChange={e => setSettings({ ...settings, widget: { ...widget, name: e.target.value } })} /></label><label>Logo URL<input type="url" maxLength="500" placeholder="https://example.com/logo.png" value={widget.logoUrl} onChange={e => setSettings({ ...settings, widget: { ...widget, logoUrl: e.target.value } })} /></label><label>Welcome message<input maxLength="280" value={widget.welcomeMessage} onChange={e => setSettings({ ...settings, widget: { ...widget, welcomeMessage: e.target.value } })} /></label><label>Primary color<div className="widget-color-field"><input type="color" value={/^#[0-9a-fA-F]{6}$/.test(widget.primaryColor) ? widget.primaryColor : '#6859cf'} onChange={e => setSettings({ ...settings, widget: { ...widget, primaryColor: e.target.value } })} /><code>{widget.primaryColor}</code></div></label><div className="widget-preview-wrap"><b>Live preview</b><div className="widget-preview" style={{ '--widget-primary': widget.primaryColor }}><header>{widget.logoUrl ? <img src={widget.logoUrl} alt="" /> : <span>✦</span>}<strong>{widget.name || profile.name}</strong></header><div className="widget-preview-body"><p>{widget.welcomeMessage}</p><small>Ask about policies, products, returns or shipping.</small></div><footer><span>Write your message…</span><button type="button" aria-label="Send preview message">➤</button></footer></div></div><div className="widget-install"><div><b>Your company widget ID</b><code>{profile.public_id}</code></div><label>Installation snippet<textarea readOnly rows="3" value={widgetSnippet} onFocus={e => e.target.select()} /></label><button type="button" className="widget-copy" onClick={() => navigator.clipboard.writeText(widgetSnippet)}><Copy size={14} /> Copy snippet</button><p>Public chat page: <a href={`/chat/${profile.slug}`} target="_blank" rel="noreferrer">{window.location.origin}/chat/{profile.slug}</a></p><details><summary>React</summary><pre>{`Add this script to your page layout (for example, in index.html or your root component):\n${widgetSnippet}`}</pre></details><details><summary>Plain HTML</summary><pre>{`Paste the snippet just before </body> on every page where chat should appear:\n${widgetSnippet}`}</pre></details><details><summary>WordPress, Shopify, Wix, or Squarespace</summary><p>Open your platform’s site-wide custom code, footer, or “code injection” area, paste the snippet, and publish. On WordPress, use a footer/header code plugin or your theme’s footer scripts area. In Shopify, use the theme’s custom liquid/footer area. In Wix or Squarespace, use the site-wide custom code settings and choose body end.</p></details></div><button type="submit">{saved ? 'Saved' : 'Save changes'}</button></form>}
      {tab === 'account' && <form className="card account-settings" onSubmit={changePassword}><h2>Change password</h2><p>Choose a new password with at least 12 characters. Other sessions will be signed out.</p><label>Current password<input required type="password" autoComplete="current-password" value={passwordForm.currentPassword} onChange={e => setPasswordForm({ ...passwordForm, currentPassword: e.target.value })} /></label><label>New password<input required minLength="12" type="password" autoComplete="new-password" value={passwordForm.newPassword} onChange={e => setPasswordForm({ ...passwordForm, newPassword: e.target.value })} /></label><label>Confirm new password<input required minLength="12" type="password" autoComplete="new-password" value={passwordForm.confirmPassword} onChange={e => setPasswordForm({ ...passwordForm, confirmPassword: e.target.value })} /></label><button type="submit">{saved ? 'Password changed' : 'Update password'}</button></form>}
    </main>
  </div>;
}
