import { useCallback, useEffect, useRef, useState } from 'react';
import { Activity, Bell, Check, ChevronDown, CircleUserRound, FileText, Headphones, MessageSquareText, Paperclip, Search, Send, ShieldAlert, UserRoundCheck } from 'lucide-react';
import { API_BASE, api, getAccessToken } from './api.js';
import { clientMessageId, createStaffRealtime } from './realtime.js';

const statuses = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'WAITING_FOR_CUSTOMER', 'WAITING_FOR_AGENT', 'RESOLVED', 'CLOSED'];
const priorities = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];

function time(value) { return value ? new Date(value).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' }) : '—'; }

export default function AgentWorkspace({ user, onActivity = () => {} }) {
  const [queue, setQueue] = useState('ALL'); const [status, setStatus] = useState(''); const [priority, setPriority] = useState('');
  const [search, setSearch] = useState(''); const [page, setPage] = useState(0); const [result, setResult] = useState({ items: [], total: 0 });
  const [agents, setAgents] = useState([]); const [selected, setSelected] = useState(null); const [thread, setThread] = useState([]); const [sla, setSla] = useState(null); const [history, setHistory] = useState({ assignments: [], statuses: [] });
  const [reply, setReply] = useState(''); const [note, setNote] = useState(''); const [resolutionReason, setResolutionReason] = useState('');
  const [availability, setAvailability] = useState('OFFLINE'); const [workloadLimit, setWorkloadLimit] = useState(10);
  const [network, setNetwork] = useState('connecting'); const [error, setError] = useState(''); const [toast, setToast] = useState('');
  const [notifications, setNotifications] = useState({ items: [], unreadCount: 0 }); const [showNotifications, setShowNotifications] = useState(false);
  const clientRef = useRef(null); const selectedRef = useRef(null); const searchTimer = useRef(null);
  const typingTimer = useRef(null);
  const isAdmin = user?.role === 'COMPANY_ADMIN';

  const loadQueue = useCallback(async () => {
    try { setResult(await api(`/api/v1/agent/workspace/conversations?queue=${queue}&status=${status}&priority=${priority}&search=${encodeURIComponent(search)}&page=${page}&size=30`)); }
    catch (e) { setError(e.message); }
  }, [queue, status, priority, search, page]);
  const loadThread = useCallback(async (conversationId) => {
    if (!conversationId) return;
    try {
      const [messages, metrics, ticketHistory] = await Promise.all([
        api(`/api/v1/agent/workspace/conversations/${conversationId}/messages`),
        api(`/api/v1/agent/workspace/conversations/${conversationId}/sla`),
        api(`/api/v1/agent/workspace/conversations/${conversationId}/history`),
      ]);
      if (selectedRef.current?.conversation_id === conversationId) { setThread(messages); setSla(metrics); setHistory(ticketHistory); }
    } catch (e) { setError(e.message); }
  }, []);
  const loadNotifications = useCallback(async () => { try { setNotifications(await api('/api/v1/agent/workspace/notifications')); } catch (e) { setError(e.message); } }, []);

  useEffect(() => { loadQueue(); }, [loadQueue]);
  useEffect(() => { api('/api/v1/agent/workspace/agents').then(rows=>{setAgents(rows);const own=rows.find(agent=>agent.id===user?.id);if(own){setAvailability(own.availability);setWorkloadLimit(Number(own.workload_limit||10));}}).catch(e => setError(e.message)); loadNotifications(); }, [loadNotifications,user?.id]);
  useEffect(() => { selectedRef.current = selected; if (selected) loadThread(selected.conversation_id); }, [selected, loadThread]);
  useEffect(() => {
    const client = createStaffRealtime((connected, frame) => {
      connected.subscribe('/user/queue/notifications', message => {
        try { const event = JSON.parse(message.body); setToast(event.summary || 'A support update arrived.'); loadNotifications(); loadQueue(); }
        catch { /* ignore malformed event */ }
      });
    }, (destination, event) => {
      if (event.type === 'MESSAGE' || event.type === 'MESSAGE_SNAPSHOT' || event.type === 'INTERNAL_NOTE' || event.type === 'TICKET_STATUS' || event.type === 'ASSIGNMENT' || event.type === 'READ') {
        loadQueue(); if (selectedRef.current?.conversation_id === destination) loadThread(destination);
      }
      if (event.type === 'TYPING' && event.senderType === 'CUSTOMER' && event.active) setToast('Customer is typing…');
    }, setNetwork);
    clientRef.current = client;
    return () => { clientRef.current = null; client.deactivate(); };
  }, [loadNotifications, loadQueue, loadThread]);
  useEffect(() => {
    const id = selected?.conversation_id; const client = clientRef.current;
    if (!id || !client?.connected) return undefined;
    const handler = frame => {
      try { const event = JSON.parse(frame.body); if (event.type === 'TYPING' && event.senderType === 'CUSTOMER' && event.active) setToast('Customer is typing…'); else { loadQueue(); loadThread(id); } }
      catch { /* ignore malformed event */ }
    };
    const publicSub = client.subscribe(`/topic/conversations/${id}`, handler);
    const privateSub = client.subscribe(`/topic/staff/conversations/${id}`, handler);
    client.publish({ destination: `/app/agent/conversations/${id}/read`, body: '{}' });
    return () => { publicSub.unsubscribe(); privateSub.unsubscribe(); };
  }, [selected, network, loadQueue, loadThread]);
  useEffect(() => { if (!toast) return undefined; const timer = setTimeout(() => setToast(''), 4000); return () => clearTimeout(timer); }, [toast]);

  async function updateAvailability(value) {
    setAvailability(value);
    try { await api('/api/v1/agent/workspace/availability', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ availability: value, workloadLimit }) }); await api('/api/v1/agent/workspace/agents').then(setAgents); }
    catch (e) { setError(e.message); }
  }
  async function assign(agentId, automatic = false) {
    if (!selected) return;
    try { await api(`/api/v1/agent/workspace/conversations/${selected.conversation_id}/assign`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ agentId: agentId || null, automatic }) }); await loadQueue(); await loadThread(selected.conversation_id); }
    catch (e) { setError(e.message); }
  }
  async function changeTicket(statusValue = null, priorityValue = null) {
    if (!selected) return;
    try { await api(`/api/v1/agent/workspace/conversations/${selected.conversation_id}/ticket`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: statusValue, priority: priorityValue, resolutionReason }) }); await loadQueue(); await loadThread(selected.conversation_id); }
    catch (e) { setError(e.message); }
  }
  function sendReply(event) {
    event?.preventDefault(); if (!selected || !reply.trim()) return;
    if (clientRef.current?.connected) clientRef.current.publish({ destination: `/app/agent/conversations/${selected.conversation_id}/typing`, body: JSON.stringify({ active: false }) });
    const body = { message: reply.trim(), clientMessageId: clientMessageId() };
    if (clientRef.current?.connected) {
      clientRef.current.publish({ destination: `/app/agent/conversations/${selected.conversation_id}/reply`, body: JSON.stringify(body) });
      setReply('');
    } else {
      api(`/api/v1/agent/conversations/${selected.conversation_id}/reply`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(() => { setReply(''); loadThread(selected.conversation_id); }).catch(e => setError(`${e.message} Reconnecting…`));
    }
  }
  async function addNote(event) { event.preventDefault(); if (!selected || !note.trim()) return; try { await api(`/api/v1/agent/workspace/conversations/${selected.conversation_id}/notes`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content: note }) }); setNote(''); await loadThread(selected.conversation_id); } catch (e) { setError(e.message); } }
  async function uploadFile(event) { const file = event.target.files?.[0]; if (!file || !selected) return; const data = new FormData(); data.append('file', file); try { await api(`/api/v1/agent/workspace/conversations/${selected.conversation_id}/attachments`, { method: 'POST', body: data }); await loadThread(selected.conversation_id); } catch (e) { setError(e.message); } finally { event.target.value = ''; } }
  async function download(file) { try { const response = await fetch(`${API_BASE}/api/v1/agent/workspace/attachments/${file.attachment_id}`, { headers: { Authorization: `Bearer ${getAccessToken()}` }, credentials: 'include' }); if (!response.ok) throw new Error('Could not download attachment.'); const url = URL.createObjectURL(await response.blob()); const a = document.createElement('a'); a.href = url; a.download = file.attachment_name || 'attachment'; a.click(); URL.revokeObjectURL(url); } catch (e) { setError(e.message); } }
  async function markNotification(notification) { if (notification.read_at) return; try { await api(`/api/v1/agent/workspace/notifications/${notification.id}/read`, { method: 'POST' }); await loadNotifications(); if (notification.conversation_id) { const item = result.items.find(row => row.conversation_id === notification.conversation_id); if (item) setSelected(item); } } catch (e) { setError(e.message); } }

  return <section className="agent-workspace" aria-label="Agent inbox">
    <nav className="agent-nav">
      <div className="agent-nav-head"><div><small>SUPPORT QUEUE</small><b>Conversation views</b></div><Headphones size={18} /></div>
      {[['UNASSIGNED','Unassigned'],['MINE','My conversations'],['ALL','All conversations']].map(([value,label])=><button key={value} className={queue===value?'selected':''} onClick={()=>{setQueue(value);setPage(0);}}><span>{label}</span><ChevronDown size={14} /></button>)}
      <hr/><small className="filter-label">TICKET STATUS</small>
      {['','OPEN','ASSIGNED','IN_PROGRESS','WAITING_FOR_CUSTOMER','WAITING_FOR_AGENT','RESOLVED','CLOSED'].map(value=><button key={value||'any'} className={`queue-filter ${status===value?'selected':''}`} onClick={()=>setStatus(value)}>{value||'Any status'}</button>)}
      <hr/><label className="availability-label">Your availability<select value={availability} onChange={e=>updateAvailability(e.target.value)}><option>OFFLINE</option><option>AVAILABLE</option><option>BUSY</option></select></label>
      {user?.role==='AGENT'&&<label className="availability-label">Workload limit<input type="number" min="1" max="100" value={workloadLimit} onChange={e=>setWorkloadLimit(Number(e.target.value))}/></label>}
      <small className="connection-state"><i className={network==='connected'?'connected':''}/>{network==='connected'?'Live updates':network==='offline'?'Offline · reconnecting':'Connecting to live updates'}</small>
    </nav>

    <section className="agent-list-panel">
      <div className="agent-list-header"><div><small>INBOX</small><h2>{queue==='MINE'?'My conversations':queue==='UNASSIGNED'?'Unassigned':'All conversations'}</h2></div><button className="notification-trigger" onClick={()=>setShowNotifications(!showNotifications)} aria-label="Notifications"><Bell size={17}/>{notifications.unreadCount>0&&<i>{notifications.unreadCount}</i>}</button>
        {showNotifications&&<div className="notification-popover"><b>Notifications</b>{notifications.items.length?notifications.items.map(n=><button key={n.id} onClick={()=>markNotification(n)} className={n.read_at?'read':''}><span>{n.summary}</span><small>{time(n.created_at)}</small></button>):<p>No notifications yet.</p>}</div>}
      </div>
      <label className="agent-search"><Search size={15}/><input value={search} onChange={e=>{setSearch(e.target.value);setPage(0);}} placeholder="Search customer or message" /></label>
      <label className="agent-priority-filter">Priority <select value={priority} onChange={e=>setPriority(e.target.value)}><option value="">All</option>{priorities.map(p=><option key={p}>{p}</option>)}</select></label>
      <div className="agent-conversation-list">
        {result.items.map(item=><button key={item.conversation_id} className={`agent-conversation ${selected?.conversation_id===item.conversation_id?'active':''}`} onClick={()=>setSelected(item)}>
          <span className="customer-avatar">{(item.customer_name||'G').slice(0,1).toUpperCase()}</span><span className="conversation-preview"><b>{item.customer_name||'Guest customer'}</b><small>{item.latest_sender==='AI'?'SupportIQ · ':item.latest_sender==='AGENT'?'Agent · ':''}{item.latest_message||item.reason||'Conversation started'}</small><small className="conversation-meta">{time(item.latest_at||item.updated_at)} · {item.ticket_status||'AI ACTIVE'}</small></span>
          <span className={`priority-dot ${String(item.priority||'NORMAL').toLowerCase()}`}/>{Number(item.unread_count)>0&&<i className="unread-count">{item.unread_count}</i>}
        </button>)}
        {!result.items.length&&<div className="agent-empty"><MessageSquareText size={22}/><b>No conversations here</b><p>Try another view or search.</p></div>}
      </div>
      <div className="agent-pagination"><span>{result.total} conversations</span><button disabled={!page} onClick={()=>setPage(p=>Math.max(0,p-1))}>Previous</button><button disabled={(page+1)*30>=result.total} onClick={()=>setPage(p=>p+1)}>Next</button></div>
    </section>

    <section className="agent-detail-panel">
      {error&&<div className="agent-inline-error" role="alert">{error}<button onClick={()=>setError('')}>Dismiss</button></div>}
      {toast&&<div className="agent-toast" role="status">{toast}</div>}
      {!selected?<div className="agent-detail-empty"><CircleUserRound size={38}/><h2>Select a conversation</h2><p>Customer replies, context, assignment, and ticket actions will appear here.</p></div>:<>
        <header className="conversation-detail-head"><div className="customer-avatar large">{(selected.customer_name||'G').slice(0,1).toUpperCase()}</div><div><h2>{selected.customer_name||'Guest customer'}</h2><small>Conversation · {selected.conversation_id.slice(0,8)} · Started {time(selected.created_at)}</small></div><button className="auto-assign" onClick={()=>assign(null,true)}><UserRoundCheck size={15}/> Auto assign</button></header>
        <div className="conversation-controls"><label>Assign to<select value={selected.assigned_agent_id||''} onChange={e=>assign(e.target.value)}><option value="">Unassigned</option>{agents.filter(a=>a.status==='ACTIVE').map(a=><option key={a.id} value={a.id}>{a.full_name} · {a.availability.toLowerCase()} · {a.workload}/{a.workload_limit}</option>)}</select></label>
          <label>Status<select value={selected.ticket_status||'OPEN'} onChange={e=>changeTicket(e.target.value,null)}>{statuses.map(s=><option key={s}>{s}</option>)}</select></label>
          <label>Priority<select value={selected.priority||'NORMAL'} onChange={e=>changeTicket(null,e.target.value)}>{priorities.map(p=><option key={p}>{p}</option>)}</select></label>
          <button className="resolve-button" onClick={()=>{if(!resolutionReason.trim()){setError('Add a resolution reason before resolving.');return;}changeTicket('RESOLVED',null)}}><Check size={15}/> Resolve</button>
        </div>
        <div className="conversation-context">
          <span><b>Assigned agent</b>{selected.assigned_agent||'Not assigned'}</span><span><b>Priority</b>{selected.priority||'NORMAL'}</span>
          {sla&&<span className={sla.firstResponseOverdue||sla.resolutionOverdue?'sla-overdue':''}><b>SLA</b>{sla.firstResponseOverdue?'First response overdue':sla.resolutionOverdue?'Resolution overdue':`${sla.minutesWaitingForFirstResponse} min waiting`}</span>}
        </div>
        <details className="ticket-history"><summary>Assignment and status history</summary><div><b>Assignments</b>{history.assignments?.length?history.assignments.map((entry,i)=><small key={`a${i}`}>{entry.assignment_type} · {entry.agent||'Unassigned'} · {time(entry.created_at)}</small>):<small>No assignment changes yet.</small>}<b>Status changes</b>{history.statuses?.length?history.statuses.map((entry,i)=><small key={`s${i}`}>{entry.old_status||'New'} → {entry.new_status}{entry.actor?` · ${entry.actor}`:''} · {time(entry.created_at)}</small>):<small>No status changes yet.</small>}</div></details>
        <div className="resolution-reason"><ShieldAlert size={14}/><input value={resolutionReason} onChange={e=>setResolutionReason(e.target.value)} placeholder="Resolution reason (required to resolve)" maxLength={600}/></div>
        <div className="agent-thread" aria-live="polite">{thread.map((m,i)=><article key={m.id||i} className={`agent-message ${m.visibility==='INTERNAL'?'internal-note':m.sender_type==='CUSTOMER'?'from-customer':m.sender_type==='AGENT'?'from-agent':'from-ai'}`}>
          <div className="agent-message-meta"><b>{m.visibility==='INTERNAL'?`Private note · ${m.author||'Agent'}`:m.sender_type==='AI'?'SupportIQ AI':m.sender_type==='AGENT'?(m.author||'Human agent'):'Customer'}</b><time>{time(m.created_at)}</time>{m.delivery_status&&<small>{m.delivery_status.toLowerCase()}</small>}</div><p>{m.content}</p>
          {m.attachment_id&&<button className="attachment-download" onClick={()=>download(m)}><FileText size={14}/>{m.attachment_name} · {Math.ceil(Number(m.attachment_size||0)/1024)} KB</button>}
          {m.sender_type==='AI'&&m.sources&&<small>Sources: {Array.isArray(m.sources)?m.sources.join(', '):String(m.sources)}</small>}
        </article>)}{!thread.length&&<p className="thread-waiting">Loading conversation…</p>}</div>
        <form className="agent-reply-form" onSubmit={sendReply}><textarea value={reply} onChange={e=>{setReply(e.target.value);if(clientRef.current?.connected){clientRef.current.publish({destination:`/app/agent/conversations/${selected.conversation_id}/typing`,body:JSON.stringify({active:Boolean(e.target.value)})});clearTimeout(typingTimer.current);typingTimer.current=setTimeout(()=>clientRef.current?.publish({destination:`/app/agent/conversations/${selected.conversation_id}/typing`,body:JSON.stringify({active:false})}),1200);}}} placeholder="Write a customer-visible reply…" maxLength={4000}/><div><label className="attach-button"><Paperclip size={15}/> Attach<input type="file" accept=".png,.jpg,.jpeg,.pdf,.txt" onChange={uploadFile}/></label><small>Messages are saved before delivery.</small><button disabled={!reply.trim()}><Send size={15}/> Send reply</button></div></form>
        <form className="internal-note-form" onSubmit={addNote}><label><MessageSquareText size={14}/> Private internal note</label><textarea value={note} onChange={e=>setNote(e.target.value)} placeholder="Visible only to your company team…" maxLength={4000}/><button disabled={!note.trim()}>Add note</button></form>
      </>}
    </section>
  </section>;
}
