(function () {
  const script = document.currentScript;
  if (!script) return;
  const companyId = script.dataset.company;
  if (!companyId) { console.error('SupportIQ widget is missing data-company.'); return; }
  const apiBase = (script.dataset.apiBase || window.location.origin).replace(/\/$/, '');
  const storageKey = `supportiq-widget:${companyId}`;
  const host = document.createElement('div');
  host.setAttribute('data-supportiq-widget', companyId);
  document.body.appendChild(host);
  const root = host.attachShadow({ mode: 'open' });
  const style = document.createElement('style');
  style.textContent = `:host{all:initial;--primary:#6859cf;font-family:Inter,system-ui,-apple-system,sans-serif;color:#263047}*{box-sizing:border-box}.launcher{position:fixed;z-index:2147483000;right:24px;bottom:24px;width:58px;height:58px;border:0;border-radius:50%;background:var(--primary);color:white;box-shadow:0 8px 28px #18223840;font-size:25px;cursor:pointer}.launcher[data-unread]:after{content:attr(data-unread);position:absolute;right:-3px;top:-3px;min-width:19px;height:19px;border-radius:12px;background:#e45454;color:white;font:700 11px/19px system-ui}.panel{position:fixed;z-index:2147483000;right:24px;bottom:94px;width:min(370px,calc(100vw - 28px));height:min(540px,calc(100vh - 120px));min-height:380px;background:white;border:1px solid #e6eaf1;border-radius:16px;box-shadow:0 16px 55px #18223835;display:none;overflow:hidden}.panel.open{display:flex;flex-direction:column}.head{height:66px;flex:none;background:var(--primary);color:white;display:flex;align-items:center;gap:11px;padding:0 16px}.logo{height:37px;width:37px;border-radius:11px;background:#ffffff25;object-fit:cover}.name{font-weight:700;font-size:14px;flex:1}.close{border:0;background:transparent;color:white;font-size:23px;cursor:pointer}.track-order{border:1px solid #ffffff75;border-radius:7px;background:#ffffff20;color:white;padding:6px;font-size:9px;cursor:pointer}.body{flex:1;overflow:auto;padding:17px;background:#fbfcff}.welcome{font-size:13px;line-height:1.55;background:#f0f2f7;border-radius:4px 13px 13px 13px;padding:12px 13px;margin-bottom:13px;white-space:pre-wrap}.message{font-size:13px;line-height:1.55;border-radius:13px;padding:10px 12px;margin:8px 0;max-width:88%;white-space:pre-wrap;overflow-wrap:anywhere}.customer{background:var(--primary);color:white;margin-left:auto;border-radius:13px 4px 13px 13px}.assistant{background:#f0f2f7}.note{font-size:10px;color:#8a94a5;margin:4px 5px 12px}.compose{display:flex;gap:7px;padding:10px;border-top:1px solid #edf0f4}.input{min-width:0;flex:1;border:1px solid #dfe4ec;border-radius:9px;padding:11px;font-size:13px;outline:0}.input:focus{border-color:var(--primary)}.send{border:0;border-radius:9px;background:var(--primary);color:white;padding:0 14px;font-size:15px;cursor:pointer}.send:disabled{opacity:.5;cursor:wait}.powered{text-align:center;padding:0 0 8px;color:#9aa3b1;font-size:9px}.error{color:#a34e32;font-size:11px;padding:8px}.order-view{display:grid;gap:9px;font:12px/1.5 system-ui;color:#344057}.order-view h3{margin:0}.order-view label{display:grid;gap:5px;font-size:10px}.order-view input,.order-view select,.order-view textarea{width:100%;padding:9px;border:1px solid #dfe3ea;border-radius:7px;background:white;font:12px system-ui}.order-view button{padding:9px;border:0;border-radius:7px;background:var(--primary);color:white;font-weight:700;cursor:pointer}.order-card{padding:12px;background:white;border:1px solid #e3e6ee;border-radius:9px}.order-card small{display:block;color:#738097}.hidden{display:none}@media(max-width:480px){.launcher{right:14px;bottom:14px}.panel{right:8px;bottom:82px;width:calc(100vw - 16px);height:calc(100vh - 100px)}}`;
  root.appendChild(style);

  const panel = document.createElement('section'); panel.className = 'panel'; panel.setAttribute('aria-label', 'Company customer support chat');
  const launcher = document.createElement('button'); launcher.className = 'launcher'; launcher.type = 'button'; launcher.setAttribute('aria-label', 'Open customer support chat'); launcher.textContent = '✦';
  root.append(launcher, panel);
  const state = { config: null, conversationId: '', token: '', orderToken: '', view: 'chat', busy: false, messages: [], ws: null, connected: false, reconnectTimer: null, buffer: '', feedbackSent: false };
  try { Object.assign(state, JSON.parse(sessionStorage.getItem(storageKey) || '{}')); } catch { /* start a fresh customer conversation */ }
  const persist = () => sessionStorage.setItem(storageKey, JSON.stringify({ conversationId: state.conversationId, token: state.token, orderToken: state.orderToken }));
  const apiUrl = path => `${apiBase}${path}`;
  const label = (tag, className, value) => { const node = document.createElement(tag); node.className = className; if (value != null) node.textContent = value; return node; };

  function drawMessages(rows) {
    if (state.view === 'orders') return;
    body.replaceChildren();
    if (!rows.length && state.config) body.appendChild(label('div', 'welcome', state.config.welcomeMessage || 'Hello! How can we help?'));
    rows.forEach(row => {
      body.appendChild(label('div', `message ${row.sender_type === 'CUSTOMER' ? 'customer' : 'assistant'}`, row.content));
      if (row.created_at) body.appendChild(label('div', 'note', `${new Date(row.created_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}${row.sender_type === 'CUSTOMER' && row.delivery_status ? ` · ${row.delivery_status.toLowerCase()}` : ''}`));
      if (row.sender_type === 'AGENT') body.appendChild(label('div', 'note', 'Human support agent'));
      const refs = row.source_refs || row.sourceReferences || [];
      if (refs.length) body.appendChild(label('div', 'note', 'Source: ' + refs.map(ref => `${ref.documentName || ref.document_name}${ref.pageNumber || ref.page_number ? ` · p. ${ref.pageNumber || ref.page_number}` : ''}${ref.section ? ` · ${ref.section}` : ''}`).join(' · ')));
      if (row.escalated) body.appendChild(label('div', 'note', 'A support agent will join this conversation.'));
    });
    if (rows.some(row => ['RESOLVED','CLOSED'].includes(row.conversation_status))) {
      const feedback = label('div', 'note feedback', state.feedbackSent ? 'Thanks for your feedback.' : 'How was your support experience?');
      if (!state.feedbackSent) {
        let rating = 0;
        for (let value = 1; value <= 5; value++) { const star=label('button','feedback-star','★');star.type='button';star.setAttribute('aria-label',`${value} stars`);star.addEventListener('click',()=>{rating=value;for(const [index,button] of [...feedback.querySelectorAll('.feedback-star')].entries())button.classList.toggle('chosen',index<rating);});feedback.appendChild(star); }
        const submit=label('button','feedback-send','Send');submit.type='button';submit.addEventListener('click',async()=>{if(!rating)return;try{await request(`/api/public/widget/${encodeURIComponent(companyId)}/conversations/${encodeURIComponent(state.conversationId)}/feedback`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({rating})});state.feedbackSent=true;drawMessages(state.messages);}catch(e){showError(e.message);}});feedback.appendChild(submit);
      }
      body.appendChild(feedback);
    }
    body.scrollTop = body.scrollHeight;
  }

  const header = document.createElement('div'); header.className = 'head';
  const body = document.createElement('div'); body.className = 'body'; body.setAttribute('aria-live', 'polite');
  const form = document.createElement('form'); form.className = 'compose';
  const input = document.createElement('input'); input.className = 'input'; input.placeholder = 'Write your message…'; input.maxLength = 4000; input.setAttribute('aria-label', 'Write your message');
  const send = document.createElement('button'); send.className = 'send'; send.type = 'submit'; send.textContent = '➤'; send.setAttribute('aria-label', 'Send message');
  const powered = label('div', 'powered', 'Powered by SupportIQ');
  const error = label('div', 'error', ''); error.classList.add('hidden');
  form.append(input, send); panel.append(header, body, error, form, powered);
  launcher.addEventListener('click', () => { panel.classList.toggle('open'); if (panel.classList.contains('open')) { launcher.removeAttribute('data-unread'); input.focus(); } });

  function showError(message) { error.textContent = message; error.classList.remove('hidden'); }
  async function request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (state.token) headers.set('X-Conversation-Token', state.token);
    const response = await fetch(apiUrl(path), { ...options, headers, mode: 'cors', credentials: 'omit' });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || 'Unable to reach company support.');
    return data;
  }
  async function orderRequest(path, options = {}) {
    const headers = new Headers(options.headers || {}); if (state.orderToken) headers.set('X-Order-Access-Token', state.orderToken);
    const response = await fetch(apiUrl(path), { ...options, headers, mode: 'cors', credentials: 'omit' }); const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.message || data.error || 'Order lookup could not be completed.'); return data;
  }
  function orderField(parent, labelText, type, name, autocomplete) {
    const labelNode=label('label','',labelText); const field=document.createElement('input'); field.type=type; field.name=name; field.required=true; field.autocomplete=autocomplete; field.maxLength=type==='text'?64:320; labelNode.appendChild(field); parent.appendChild(labelNode); return field;
  }
  function orderViewError(parent,message) { parent.querySelector('.order-error')?.remove(); const node=label('div','order-error',message); node.style.color='#a34e32'; parent.prepend(node); }
  async function showVerifiedOrder() {
    body.replaceChildren(); body.appendChild(label('div','order-view','Loading verified order…'));
    try {
      const [order,history,refunds]=await Promise.all([orderRequest('/api/public/orders/current'),orderRequest('/api/public/orders/current/history'),orderRequest('/api/public/orders/current/refunds')]);
      body.replaceChildren(); const view=label('section','order-view'); const title=label('h3','',`Order ${order.order_number}${order.is_demo?' · DEMO':''}`); view.appendChild(title);
      const card=label('div','order-card'); card.appendChild(label('b','',`${String(order.fulfillment_status).replaceAll('_',' ')} · ${String(order.payment_status).replaceAll('_',' ')}`)); card.appendChild(label('small','',`Total ${order.currency} ${order.total_amount}`)); card.appendChild(label('small','',order.tracking_number?`Tracking ${order.tracking_number}`:'Tracking number not available')); card.appendChild(label('small','',`Placed ${new Date(order.ordered_at).toLocaleString()}`)); view.appendChild(card);
      if(history.length>1){view.appendChild(label('b','','Your order history'));history.forEach(row=>view.appendChild(label('small','','Order '+row.order_number+' · '+String(row.fulfillment_status).replaceAll('_',' ')+' · '+row.currency+' '+row.total_amount)));}
      view.appendChild(label('b','','Refund status')); if(!refunds.length)view.appendChild(label('small','','No refund request is recorded.')); refunds.forEach(r=>{const item=label('div','order-card');item.appendChild(label('b','',String(r.status).replaceAll('_',' ')));item.appendChild(label('small','',`${r.currency} ${r.approved_amount||r.requested_amount} · updated ${new Date(r.updated_at).toLocaleString()}`));if(r.provider_reference)item.appendChild(label('small','',`Provider reference ${r.provider_reference}`));if(r.arn)item.appendChild(label('small','',`ARN ${r.arn}`));view.appendChild(item);});
      const form=document.createElement('form');form.className='order-view';form.appendChild(label('h3','','Request a refund'));form.appendChild(label('small','','Requests are reviewed by the company. This does not approve or send a payment.'));
      const amount=orderField(form,'Amount','number','amount','off');amount.min='0.01';amount.max=String(order.total_amount);amount.step='0.01';amount.value=String(order.total_amount);
      const conditionLabel=label('label','','Product condition');const condition=document.createElement('select');condition.name='condition';['UNOPENED','UNUSED','DAMAGED','WRONG_ITEM','OTHER'].forEach(v=>{const o=document.createElement('option');o.value=v;o.textContent=v.replaceAll('_',' ');condition.appendChild(o);});conditionLabel.appendChild(condition);form.appendChild(conditionLabel);
      const category=orderField(form,'Product category','text','category','off');category.value='GENERAL';
      const reasonLabel=label('label','','Reason');const reason=document.createElement('textarea');reason.name='reason';reason.required=true;reason.maxLength=1000;reason.rows=3;reasonLabel.appendChild(reason);form.appendChild(reasonLabel);
      const submit=label('button','','Submit refund request');submit.type='submit';form.appendChild(submit);
      form.addEventListener('submit',async event=>{event.preventDefault();submit.disabled=true;try{await orderRequest('/api/public/orders/current/refunds',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID?.()||`${Date.now()}-${Math.random()}`},body:JSON.stringify({amount:amount.value,condition:condition.value,category:category.value,reason:reason.value})});await showVerifiedOrder();}catch(e){orderViewError(view,e.message);submit.disabled=false;}});
      view.appendChild(form);const again=label('button','','Verify another order');again.type='button';again.addEventListener('click',()=>{state.orderToken='';persist();renderOrderLookup();});view.appendChild(again);body.appendChild(view);
    } catch(e) { state.orderToken='';persist();renderOrderLookup(e.message); }
  }
  function renderOrderLookup(message='') {
    body.replaceChildren(); const view=label('section','order-view');view.appendChild(label('h3','','Track your order'));
    if(message)orderViewError(view,message);
    const form=document.createElement('form');form.className='order-view';
    const orderNumber=orderField(form,'Order number','text','orderNumber','off');const email=orderField(form,'Order email','email','email','email');const postal=orderField(form,'Delivery postal code','text','postalCode','postal-code');postal.minLength=3;postal.maxLength=16;
    const button=label('button','','Verify order');button.type='submit';form.appendChild(button);
    form.addEventListener('submit',async event=>{event.preventDefault();button.disabled=true;try{const data=await orderRequest('/api/public/orders/verify',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({publicCompanyId:state.config.publicId,orderNumber:orderNumber.value,email:email.value,postalCode:postal.value})});state.orderToken=data.accessToken;persist();await showVerifiedOrder();}catch(e){orderViewError(view,e.message);button.disabled=false;}});
    view.appendChild(form);body.appendChild(view);
  }
  async function poll() {
    if (!state.conversationId || !state.token) return;
    try {
      const rows = await request(`/api/public/widget/${encodeURIComponent(companyId)}/conversations/${encodeURIComponent(state.conversationId)}/messages`);
      state.messages = rows; drawMessages(rows);
    } catch (e) { if (e.message) showError(e.message); }
  }
  function stomp(command, headers = {}, body = '') {
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
    const lines = [command, ...Object.entries(headers).map(([key, value]) => `${key}:${value}`)];
    state.ws.send(`${lines.join('\n')}\n\n${body}\0`);
  }
  function connectRealtime() {
    if (!state.conversationId || !state.token || !state.config?.publicId || state.ws?.readyState === WebSocket.OPEN) return;
    clearTimeout(state.reconnectTimer);
    const wsBase = apiBase.replace(/^http/, 'ws');
    const socket = new WebSocket(`${wsBase}/ws`); state.ws = socket; state.connected = false;
    socket.onopen = () => stomp('CONNECT', { 'accept-version': '1.2', 'heart-beat': '10000,10000', 'X-Company-Public-Id': companyId, 'X-Conversation-Id': state.conversationId, 'X-Conversation-Token': state.token });
    socket.onmessage = event => {
      state.buffer += event.data;
      let end; while ((end = state.buffer.indexOf('\0')) >= 0) {
        const frame = state.buffer.slice(0, end).replace(/^\n+/, ''); state.buffer = state.buffer.slice(end + 1);
        const boundary = frame.indexOf('\n\n'); if (boundary < 0) continue;
        const command = frame.slice(0, frame.indexOf('\n')); const headers = Object.fromEntries(frame.slice(frame.indexOf('\n') + 1, boundary).split('\n').map(line => { const at = line.indexOf(':'); return at < 0 ? ['', ''] : [line.slice(0, at), line.slice(at + 1)]; }));
        if (command === 'CONNECTED') { state.connected = true; stomp('SUBSCRIBE', { id: `customer-${state.conversationId}`, destination: `/topic/conversations/${state.conversationId}`, ack: 'auto' }); stomp('SEND', { destination: `/app/customer/conversations/${state.conversationId}/read`, 'content-type': 'application/json' }, '{}'); poll(); }
        if (command === 'MESSAGE') { try { const update = JSON.parse(frame.slice(boundary + 2)); if (update.type === 'MESSAGE_SNAPSHOT') { state.messages = update.messages || []; drawMessages(state.messages); } else if (update.type === 'MESSAGE' && update.message) { if (!state.messages.some(row => row.id === update.message.id)) state.messages.push(update.message); drawMessages(state.messages); if (!panel.classList.contains('open') && update.message.sender_type !== 'CUSTOMER') launcher.dataset.unread = String(Number(launcher.dataset.unread || 0) + 1); } else if (update.type === 'TYPING' && update.senderType === 'AGENT') { body.querySelector('.typing')?.remove(); if (update.active) body.appendChild(label('div', 'note typing', 'A support agent is typing…')); } else if (update.type === 'TICKET_STATUS') poll(); } catch { /* ignore malformed realtime events */ } }
        if (command === 'ERROR') showError('Live connection needs to reconnect. Your conversation is saved.');
      }
    };
    socket.onclose = () => { state.connected = false; if (panel.classList.contains('open')) showError('Reconnecting… your conversation is saved.'); state.reconnectTimer = setTimeout(connectRealtime, 2500); };
    socket.onerror = () => { state.connected = false; };
  }
  form.addEventListener('submit', async event => {
    event.preventDefault(); const message = input.value.trim();
    if (!message || state.busy) return;
    state.busy = true; send.disabled = true; input.value = ''; error.classList.add('hidden');
    const optimistic = [...state.messages, { sender_type: 'CUSTOMER', content: message }]; drawMessages(optimistic);
    try {
      if (state.conversationId && state.connected) {
        stomp('SEND', { destination: `/app/customer/conversations/${state.conversationId}/send`, 'content-type': 'application/json' }, JSON.stringify({ message, clientMessageId: crypto.randomUUID?.() || `${Date.now()}-${Math.random()}` }));
        state.messages = optimistic; persist(); return;
      }
      if (state.conversationId && !state.connected) throw new Error('Connection interrupted. Reconnecting… your message was not sent.');
      const data = await request(`/api/public/widget/${encodeURIComponent(companyId)}/chat`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message, conversationId: state.conversationId || undefined, orderAccessToken: state.orderToken || undefined })
      });
      state.conversationId = data.conversationId; if (data.chatAccessToken) state.token = data.chatAccessToken; persist();
      await poll(); connectRealtime();
    } catch (e) { showError(e.message); drawMessages(state.messages); }
    finally { state.busy = false; send.disabled = false; input.focus(); }
  });

  fetch(apiUrl(`/api/public/widget/${encodeURIComponent(companyId)}/config`), { mode: 'cors', credentials: 'omit' })
    .then(response => { if (!response.ok) throw new Error('This company chat is unavailable.'); return response.json(); })
    .then(config => {
      state.config = config; root.host.style.setProperty('--primary', /^#[\da-fA-F]{6}$/.test(config.primaryColor) ? config.primaryColor : '#6859cf');
      header.replaceChildren();
      if (config.logoUrl) { const img = document.createElement('img'); img.className = 'logo'; img.src = config.logoUrl; img.alt = ''; header.appendChild(img); }
      header.appendChild(label('strong', 'name', config.widgetName || config.companyName || 'Customer support'));
      const track=label('button','track-order','Track order');track.type='button';track.addEventListener('click',()=>{state.view=state.view==='orders'?'chat':'orders';form.hidden=state.view==='orders';powered.hidden=state.view==='orders';track.textContent=state.view==='orders'?'Back to chat':'Track order';if(state.view==='orders'){if(state.orderToken)showVerifiedOrder();else renderOrderLookup();}else drawMessages(state.messages);});header.appendChild(track);
      const close = label('button', 'close', '×'); close.type = 'button'; close.setAttribute('aria-label', 'Close chat'); close.addEventListener('click', () => panel.classList.remove('open')); header.appendChild(close);
      drawMessages(state.messages); poll(); connectRealtime();
      window.setInterval(() => { if (!state.connected) poll(); }, 8000);
    })
    .catch(e => { launcher.disabled = true; showError(e.message); });
})();
