import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertCircle, BookOpen, CheckCircle2, Clock3, FileText, LoaderCircle, RefreshCw, Search, Trash2, Upload, X } from 'lucide-react';
import { API_BASE, api, getAccessToken, setAccessToken } from './api.js';

const ACCEPTED = '.pdf,.docx,.txt,.md';
const statusLabel = { UPLOADED: 'Queued', PROCESSING: 'Processing', READY: 'Ready', FAILED: 'Failed', ARCHIVED: 'Archived' };

export default function KnowledgeBase() {
  const [documents,setDocuments]=useState([]); const [search,setSearch]=useState(''); const [filter,setFilter]=useState('');
  const [progress,setProgress]=useState(null); const [error,setError]=useState(''); const [notice,setNotice]=useState('');
  const [preview,setPreview]=useState(null); const [dragging,setDragging]=useState(false); const [working,setWorking]=useState('');
  const inputRef=useRef(null);

  const load=useCallback(async()=>{
    const params=new URLSearchParams(); if(search.trim())params.set('search',search.trim()); if(filter)params.set('status',filter);
    try { setDocuments(await api(`/api/v1/knowledge${params.size?`?${params}`:''}`)); setError(''); }
    catch(e){setError(e.message);}
  },[search,filter]);
  useEffect(()=>{load();},[load]);
  useEffect(()=>{
    if(!documents.some(d=>['UPLOADED','PROCESSING'].includes(d.status)))return;
    const timer=setInterval(load,2000); return()=>clearInterval(timer);
  },[documents,load]);

  async function sendUpload(file,token) {
    return new Promise((resolve,reject)=>{
      const xhr=new XMLHttpRequest(); xhr.open('POST',`${API_BASE}/api/v1/knowledge`); xhr.withCredentials=true;
      if(token)xhr.setRequestHeader('Authorization',`Bearer ${token}`);
      xhr.upload.onprogress=e=>{if(e.lengthComputable)setProgress(Math.round(e.loaded/e.total*100));};
      xhr.onerror=()=>reject(new Error('Upload failed because the server could not be reached.'));
      xhr.onload=()=>{
        let body={};try{body=JSON.parse(xhr.responseText||'{}');}catch{}
        if(xhr.status===401){resolve({unauthorized:true});return;}
        if(xhr.status<200||xhr.status>=300){reject(new Error(body.error||body.message||'Document upload failed.'));return;}
        resolve(body);
      };
      const data=new FormData();data.append('file',file);xhr.send(data);
    });
  }
  async function upload(file) {
    if(!file)return;
    setError('');setNotice('');setProgress(0);
    try {
      let token=getAccessToken(); let result=await sendUpload(file,token);
      if(result.unauthorized){const refreshed=await api('/api/auth/refresh',{method:'POST'});setAccessToken(refreshed.accessToken);result=await sendUpload(file,refreshed.accessToken);}
      if(result.unauthorized)throw new Error('Your session expired. Sign in again.');
      setNotice(`${file.name} uploaded. Processing will finish in the background.`);await load();
    }catch(e){setError(e.message);}finally{setProgress(null);if(inputRef.current)inputRef.current.value='';}
  }
  async function retry(doc,action='retry') {
    setWorking(doc.id);setError('');setNotice('');
    try{await api(`/api/v1/knowledge/${doc.id}/${action}`,{method:'POST'});setNotice(`${doc.file_name} queued for ${action==='reprocess'?'reprocessing':'retry'}.`);await load();}
    catch(e){setError(e.message);}finally{setWorking('');}
  }
  async function remove(doc) {
    if(!window.confirm(`Remove “${doc.file_name}” from the active knowledge base?`))return;
    setWorking(doc.id);setError('');
    try{await api(`/api/v1/knowledge/${doc.id}`,{method:'DELETE'});setNotice(`${doc.file_name} was archived and removed from customer retrieval.`);await load();}
    catch(e){setError(e.message);}finally{setWorking('');}
  }
  async function showPreview(doc) {
    setWorking(doc.id);setError('');
    try{setPreview(await api(`/api/v1/knowledge/${doc.id}/preview`));}
    catch(e){setError(e.message);}finally{setWorking('');}
  }
  const drop=e=>{e.preventDefault();setDragging(false);upload(e.dataTransfer.files?.[0]);};
  const active=documents.filter(d=>d.status!=='ARCHIVED').length;
  return <section className="knowledge-manager">
    <div className="knowledge-intro"><div><small>COMPANY KNOWLEDGE</small><h2>Teach SupportIQ from approved sources.</h2><p>PDF, DOCX, TXT, or Markdown up to 10 MB. Files are processed in the background before AI can use them.</p></div><div className="knowledge-count"><b>{active}</b><span>active documents</span></div></div>
    <div className={`knowledge-drop ${dragging?'dragging':''}`} onDragOver={e=>{e.preventDefault();setDragging(true);}} onDragLeave={()=>setDragging(false)} onDrop={drop}>
      <div className="knowledge-drop-icon"><Upload size={21}/></div><b>Drop policies and product guides here</b><span>PDF · DOCX · TXT · MD · up to 10 MB</span>
      <button type="button" onClick={()=>inputRef.current?.click()} disabled={progress!==null}>{progress===null?'Browse files':'Uploading…'}</button>
      <input ref={inputRef} hidden type="file" accept={ACCEPTED} onChange={e=>upload(e.target.files?.[0])}/>
      {progress!==null&&<div className="upload-progress" role="status"><div><span>Uploading document</span><b>{progress}%</b></div><i><span style={{width:`${progress}%`}}/></i></div>}
    </div>
    {error&&<div className="knowledge-alert error"><AlertCircle size={16}/>{error}<button onClick={()=>setError('')} aria-label="Dismiss error"><X size={14}/></button></div>}
    {notice&&<div className="knowledge-alert success"><CheckCircle2 size={16}/>{notice}<button onClick={()=>setNotice('')} aria-label="Dismiss notice"><X size={14}/></button></div>}
    <div className="knowledge-tools"><label><Search size={16}/><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Search documents…" aria-label="Search documents"/></label><select aria-label="Filter document status" value={filter} onChange={e=>setFilter(e.target.value)}><option value="">All statuses</option>{Object.entries(statusLabel).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select><button type="button" onClick={load} title="Refresh document statuses"><RefreshCw size={15}/></button></div>
    <div className="knowledge-list">
      <div className="knowledge-list-heading"><b>Knowledge documents</b><span>{documents.length} shown</span></div>
      {!documents.length&&<div className="knowledge-empty"><BookOpen size={30}/><b>{search||filter?'No documents match these filters.':'Your knowledge base is empty.'}</b><p>{search||filter?'Try another search or status.':'Upload a policy, product guide, or FAQ to ground customer answers in your company sources.'}</p></div>}
      {documents.map(doc=><article className="knowledge-document" key={doc.id}>
        <div className="knowledge-file-icon"><FileText size={18}/></div><div className="knowledge-doc-main"><div className="knowledge-doc-title"><b>{doc.file_name}</b><span className={`doc-status ${doc.status?.toLowerCase()}`}>{doc.status==='PROCESSING'&&<LoaderCircle size={12} className="spin"/>}{doc.status==='READY'&&<CheckCircle2 size={12}/ >}{doc.status==='UPLOADED'&&<Clock3 size={12}/ >}{statusLabel[doc.status]||doc.status}</span></div><small>{doc.mime_type||doc.file_name.split('.').pop()?.toUpperCase()} · {formatBytes(doc.file_size)} · Version {doc.version||1}{doc.chunk_count!=null?` · ${doc.chunk_count} chunks`:''}</small>{doc.processing_error&&<p className="doc-error"><AlertCircle size={13}/>{doc.processing_error}</p>}{doc.attempt_count>0&&<small className="doc-attempt">Processing attempt {doc.attempt_count} of 5</small>}</div>
        <div className="knowledge-doc-actions"><button type="button" onClick={()=>showPreview(doc)} disabled={working===doc.id} title="Preview extracted text">Preview</button>{doc.status==='FAILED'&&<button type="button" onClick={()=>retry(doc)} disabled={working===doc.id} title="Retry processing">{working===doc.id?<LoaderCircle size={14} className="spin"/>:<RefreshCw size={14}/>} Retry</button>}{doc.status==='READY'&&<button type="button" onClick={()=>retry(doc,'reprocess')} disabled={working===doc.id} title="Rebuild chunks and embeddings">{working===doc.id?<LoaderCircle size={14} className="spin"/>:<RefreshCw size={14}/>} Reprocess</button>}{doc.status!=='ARCHIVED'&&<button type="button" className="delete-doc" onClick={()=>remove(doc)} disabled={working===doc.id} title="Remove document"><Trash2 size={15}/></button>}</div>
      </article>)}
    </div>
    {preview&&<div className="knowledge-modal-backdrop" onMouseDown={e=>e.target===e.currentTarget&&setPreview(null)}><section className="knowledge-preview-modal" role="dialog" aria-modal="true" aria-label="Document preview"><header><div><b>{preview.file_name}</b><small>{statusLabel[preview.status]} · Version {preview.version||1}</small></div><button type="button" onClick={()=>setPreview(null)} aria-label="Close preview"><X size={18}/></button></header>{preview.processing_error&&<p className="doc-error"><AlertCircle size={13}/>{preview.processing_error}</p>}<pre>{preview.content||'No extracted text is available yet. Check the processing status and try again.'}</pre></section></div>}
  </section>;
}

function formatBytes(value){const size=Number(value)||0;if(size<1024)return `${size} B`;if(size<1048576)return `${(size/1024).toFixed(1)} KB`;return `${(size/1048576).toFixed(1)} MB`;}
