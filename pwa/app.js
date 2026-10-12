'use strict';
const KEY='sg-delivery-pwa-v2',OLD='sg-delivery-pwa-v1';
const $=id=>document.getElementById(id);
const safe=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;','\'':'&#39;'}[c]));
const id=()=>globalThis.crypto?.randomUUID?.()||String(Date.now())+Math.random().toString(36).slice(2);
const today=()=>new Date().toLocaleDateString('en-CA');
const dateTime=t=>t?new Date(t).toLocaleString('en-SG'):'—';
const timeText=t=>{if(!t)return '—';const [h,m]=t.split(':').map(Number);return new Date(2020,0,1,h,m).toLocaleTimeString('en-SG',{hour:'numeric',minute:'2-digit'})};
let tab='home',selectedStop=0,selectedHistory=null,proofType='delivery',noticeTimer;
const defaults=()=>({routes:[],current:null,settings:{start:'Woodlands Checkpoint',address:'21 Woodlands Crossing, Singapore 738203',minutes:8,date:today(),time:'10:00'},draft:''});
let state=defaults();
try{const raw=localStorage.getItem(KEY);if(raw){const s=JSON.parse(raw);if(Array.isArray(s.routes))state={...defaults(),...s,settings:{...defaults().settings,...s.settings}}}else{const old=JSON.parse(localStorage.getItem(OLD)||'null');if(old?.routes?.length){state.routes=old.routes.map(r=>({id:r.id||id(),created:r.created||new Date().toISOString(),title:r.title||'Singapore Delivery',stops:r.stops.map(s=>({id:id(),postal:s.postal||s.postalCode||'',status:s.status||'Pending',deliveredAt:s.deliveredAt||null})),start:'Woodlands Checkpoint',address:'21 Woodlands Crossing, Singapore 738203',date:today(),time:'10:00',minutes:8}));state.current=old.current}}}catch(e){console.warn('Could not read saved routes',e)}
const active=()=>state.routes.find(r=>r.id===state.current);
function persist(){try{localStorage.setItem(KEY,JSON.stringify(state));return true}catch(e){toast('Storage full. Export backup before adding more photos.');return false}}
function toast(message){$('toast').textContent=message;$('toast').style.display='block';clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>$('toast').style.display='none',3600)}
function navigate(name){tab=name;document.querySelectorAll('#tabs button').forEach(b=>b.classList.toggle('selected',b.dataset.tab===tab));render();window.scrollTo(0,0)}
document.querySelectorAll('#tabs button').forEach(b=>b.addEventListener('click',()=>navigate(b.dataset.tab)));
$('back').onclick=()=>navigate('home');
$('settings').onclick=()=>{const v=prompt('Delivery time per stop (minutes):',state.settings.minutes);if(v===null)return;const n=Number(v);if(!Number.isInteger(n)||n<1||n>120)return toast('Enter 1–120 minutes');state.settings.minutes=n;persist();render()};
const match=(s,re)=>s.match(re)?.[1]?.trim()||'';
function parseOrders(text){
 const parts=text.split(/(?=\[\d{1,2}\/\d{1,2}[^\]]*\][^\n]*:)|(?=🔥\s*ORDER\b)/i).filter(s=>s.trim());
 const blocks=parts.length?parts:[text];const seen=new Set(),out=[];let duplicates=0;
 for(const block of blocks){
  const postal=(block.match(/(?<!\d)\d{6}(?!\d)/g)||[]).find(x=>x!=='000000');if(!postal)continue;
  if(seen.has(postal)){duplicates++;continue}seen.add(postal);
  const name=match(block,/(?:👤\s*)?(?:Nama|Customer|Name)\s*:\s*([^\n\r]+)/i);
  const phone=match(block,/(?:Phone|WhatsApp|Tel|Mobile)\s*:\s*(?:https?:\/\/wa\.me\/)?(\+?65[\s-]?\d{4}[\s-]?\d{4}|\d{8})/i)||match(block,/(?:wa\.me\/)(\+?65\d{8})/i);
  const amount=match(block,/(?:Total|Amount|Parcel Price|Price)\s*:\s*(?:S?\$|SGD\s*)?\s*(\d+(?:\.\d{1,2})?)/i);
  const payment=/\bPAYNOW\b/i.test(block)?'PayNow':/\bCOD\b|CASH ON DELIVERY/i.test(block)?'Cash On Delivery':'Not specified';
  const address=match(block,/(?:📍\s*)?(?:Alamat|Address)\s*:\s*([\s\S]*?)(?=\n\s*(?:📱|WhatsApp|Phone|🛒|Item|Total|💰|━━━━━━━━|$))/i).replace(/\n/g,' ').trim();
  out.push({id:id(),postal,name,phone,amount,payment,address,status:'Pending',deliveredAt:null,proofDelivery:null,proofPayment:null});
 }
 return {stops:out,duplicates,failed:Math.max(0,blocks.length-out.length-duplicates)};
}
function saveSettingsFromHome(){
 const start=$('startName')?.value?.trim(),address=$('startAddress')?.value?.trim(),date=$('deliveryDate')?.value,time=$('startTime')?.value;
 if(start)state.settings.start=start;if(address)state.settings.address=address;
 if(date)state.settings.date=date;if(time)state.settings.time=time;
 state.draft=$('codes')?.value||state.draft;persist();
}
function home(){
 const s=state.settings;
 return '<h1>Plan your delivery</h1><p class="hint">Plan a round trip from your selected Start & End location.</p>'+
 '<div class="outline gold-border"><div class="eyebrow">Start & End</div><div class="title-strong" id="startTitle">'+safe(s.start)+'</div><p class="muted" style="margin-top:18px">'+safe(s.address)+'</p><p class="hint">Your route returns to this location after the final delivery.</p><details><summary class="btn gold wide" style="list-style:none">Change</summary><label class="hint">Location name</label><input class="input" id="startName" value="'+safe(s.start)+'"><div class="space"></div><label class="hint">Address</label><input class="input" id="startAddress" value="'+safe(s.address)+'"><div class="space"></div><button class="btn wide" id="saveStart">Save location</button></details></div>'+
 '<div class="grid2"><div class="field-card"><label for="deliveryDate">Delivery Date</label><input type="date" id="deliveryDate" value="'+safe(s.date)+'"></div><div class="field-card"><label for="startTime">Start time</label><input type="time" id="startTime" value="'+safe(s.time)+'"></div></div>'+
 '<p class="hint" style="margin:18px 0">Delivery time per stop: '+s.minutes+' minutes</p><textarea id="codes" placeholder="Singapore postal codes or paste WhatsApp orders">'+safe(state.draft)+'</textarea>'+
 '<button class="btn outline-btn wide" style="margin-top:12px" id="paste">Import WhatsApp Orders</button>'+
 '<div class="stats" style="margin-top:12px"><div class="stat"><strong id="validCount">0</strong><span>Valid stops</span></div><div class="stat"><strong id="dupCount">0</strong><span>Duplicates removed</span></div></div>'+
 '<div class="grid2" style="margin-top:12px"><button class="btn" id="optimize">Create route</button><button class="btn outline-btn" id="clear">Clear postal code</button></div>'+
 '<button class="btn outline-btn wide gold" style="margin-top:12px;border-color:var(--gold)" disabled>▤ &nbsp; Tax Declare (coming later)</button>'+
 '<p class="hint" style="margin-top:15px">Supports up to 50 unique stops. V2 currently uses import order; real road-based optimization and driving ETA are not yet available.</p>';
}
function routeView(){
 const r=active();if(!r)return '<div class="card"><h2>Your next delivery starts here</h2><p class="hint">Plan a route on Home or open a saved route from History.</p></div><button class="btn wide tall" id="plan">Plan a route</button>';
 const done=r.stops.filter(s=>s.status==='Delivered').length;
 return '<h1>Route overview</h1><p class="hint">'+safe(r.date)+' • '+timeText(r.time)+' • '+r.stops.length+' delivery stops • '+done+' delivered</p>'+
 '<div class="card"><h2>'+safe(r.start)+'</h2><p class="muted">'+safe(r.address)+'</p><p class="hint">Stop order is editable manually. Drive-time ETA and distance are not calculated in this version.</p><div class="grid2"><div class="stat"><strong>'+r.stops.length+'</strong><span>Stops</span></div><div class="stat"><strong>'+(r.stops.length*r.minutes)+'</strong><span>Stop-service minutes (excludes driving)</span></div></div></div>'+
 r.stops.map((s,i)=>'<div class="outline"><div class="stop-head"><strong>'+(i+1)+'. '+safe(s.postal)+'</strong><span class="pill '+safe(s.status.toLowerCase())+'">'+safe(s.status)+'</span></div><p class="hint">'+safe(s.address||s.name||'Singapore')+'</p><div class="grid2"><button class="btn outline-btn" data-move="'+i+'" data-dir="-1" '+(i===0?'disabled':'')+'>↑ Earlier</button><button class="btn outline-btn" data-move="'+i+'" data-dir="1" '+(i===r.stops.length-1?'disabled':'')+'>↓ Later</button></div><div class="space"></div><button class="btn wide" data-openstop="'+i+'">Open delivery</button></div>').join('');
}
function deliveryView(){
 const r=active();if(!r)return '<div class="card"><h2>Your next delivery starts here</h2><p class="hint">Plan a route on Home or open a saved route from History.</p></div><button class="btn wide" id="plan">Plan a route</button>';
 selectedStop=Math.max(0,Math.min(selectedStop,r.stops.length-1));const s=r.stops[selectedStop];if(!s)return '<p>No stops.</p>';
 return '<div class="outline gold-border"><div class="stop-head"><h2 class="gold">Stop '+(selectedStop+1)+' of '+r.stops.length+'</h2><button class="btn cyan" id="message">Send message</button></div>'+
 '<div class="title-strong">'+safe(s.postal)+'</div><div class="delivery-info"><p>'+safe(s.address||'Singapore '+s.postal)+'</p><p>Customer: '+safe(s.name||'Not provided')+'</p><p>Phone: '+safe(s.phone||'Not provided')+'</p><p>Parcel Price: '+(s.amount?'$'+safe(s.amount):'Not provided')+'</p><p>Payment Mode: <span class="pill '+(s.payment==='PayNow'?'paynow':'cash')+'">'+safe(s.payment||'Not specified')+'</span></p><span class="pill '+safe(s.status.toLowerCase())+'">'+safe(s.status)+'</span></div><hr style="border-color:var(--line)"><p class="hint">Start time: '+timeText(r.time)+' • '+r.minutes+' minutes per stop (excluding driving)</p>'+
 (s.deliveredAt?'<p class="hint">Delivered: '+dateTime(s.deliveredAt)+'</p>':'')+'</div>'+
 '<div class="grid2"><button class="btn outline-btn" data-proof="delivery">Proof of Delivery '+(s.proofDelivery?'✓':'')+'</button><button class="btn outline-btn" data-proof="payment">Proof of Payment '+(s.proofPayment?'✓':'')+'</button></div>'+
 (s.proofDelivery?'<p><img class="photo" src="'+safe(s.proofDelivery)+'" alt="Proof of delivery"></p>':'')+
 (s.proofPayment?'<p><img class="photo" src="'+safe(s.proofPayment)+'" alt="Proof of payment"></p>':'')+
 '<div class="grid2" style="margin-top:12px"><button class="btn" id="nav">Navigate</button><button class="btn green" data-status="Delivered">Delivered</button><button class="btn amber" data-status="Hold">On Hold</button><button class="btn gray" data-status="Skipped">Skipped</button></div>'+
 '<button class="btn outline-btn wide" style="margin-top:12px" id="next" '+(selectedStop===r.stops.length-1?'disabled':'')+'>Next stop →</button>'+
 '<button class="btn outline-btn wide" style="margin-top:12px" id="prev" '+(selectedStop===0?'disabled':'')+'>← Previous stop</button>';
}
function mapView(){
 const r=active();return '<h1>Delivery map</h1><p class="hint">Singapore map overview. The road route and delivery pins are not plotted yet.</p>'+
 '<iframe class="map-frame" title="OpenStreetMap Singapore" loading="lazy" referrerpolicy="no-referrer" src="https://www.openstreetmap.org/export/embed.html?bbox=103.60%2C1.22%2C104.10%2C1.48&amp;layer=mapnik"></iframe>'+
 '<p class="hint">Map requires internet. Select a stop below to open navigation in Google Maps.</p>'+
 (r?r.stops.map((s,i)=>'<div class="stop"><div class="stop-head"><strong>'+(i+1)+'. '+safe(s.postal)+'</strong><button class="btn" data-mapstop="'+i+'">Navigate</button></div></div>').join(''):'<p class="muted">Create a delivery route first.</p>');
}
function historyView(){
 return '<h1>Route history</h1><p class="hint">Tap a date to view customer details and proof photos without changing today’s route.</p>'+
 '<div class="grid2"><button class="btn light" id="backup">Backup All History</button><button class="btn outline-btn" id="restore">Restore Backup</button></div>'+
 '<p class="hint">Backups contain customer addresses, phone numbers and proof photos. Keep them private: JSON backups are not encrypted.</p><p class="gold">Select history</p>'+
 (state.routes.length?state.routes.map(r=>'<button class="card history-item" data-history="'+safe(r.id)+'"><h2>'+safe(r.date||r.created.slice(0,10))+'</h2><div class="history-line"><span>Total Parcel</span><strong>'+r.stops.length+'</strong></div><div class="history-line"><span>Success Rate</span><strong>'+((r.stops.filter(s=>s.status==='Delivered').length/Math.max(1,r.stops.length))*100).toFixed(1)+'%</strong></div><div class="history-line"><span>Total Distance</span><strong>Not available</strong></div><div class="history-line"><span>Finish Time</span><strong>Not calculated</strong></div></button>').join(''):'<p class="muted">No saved history.</p>')+
 (selectedHistory?historyDetails(selectedHistory):'');
}
function historyDetails(key){
 const r=state.routes.find(x=>x.id===key);if(!r)return '';
 return '<div class="outline"><h2>'+safe(r.date||'Route')+' — read only</h2>'+r.stops.map((s,i)=>'<details><summary>'+(i+1)+'. '+safe(s.postal)+' — '+safe(s.status)+'</summary><p class="hint">Customer: '+safe(s.name||'—')+'<br>Phone: '+safe(s.phone||'—')+'<br>Address: '+safe(s.address||'—')+'<br>Delivered: '+dateTime(s.deliveredAt)+'</p>'+(s.proofDelivery?'<img class="photo" src="'+safe(s.proofDelivery)+'" alt="Delivery proof">':'')+(s.proofPayment?'<img class="photo" src="'+safe(s.proofPayment)+'" alt="Payment proof">':'')+'</details>').join('')+'</div>';
}
function bindHome(){
 const update=()=>{const x=parseOrders($('codes').value);$('validCount').textContent=x.stops.length;$('dupCount').textContent=x.duplicates;state.draft=$('codes').value;persist()};
 $('codes').addEventListener('input',update);update();
 $('saveStart')?.addEventListener('click',()=>{saveSettingsFromHome();render();toast('Start/End saved')});
 $('deliveryDate').onchange=saveSettingsFromHome;$('startTime').onchange=saveSettingsFromHome;
 $('paste').onclick=async()=>{try{const t=await navigator.clipboard.readText();$('codes').value=t;update();toast('WhatsApp text imported')}catch{toast('Paste WhatsApp text into the box manually (press and hold).')}};
 $('clear').onclick=()=>{state.draft='';persist();render()};
 $('optimize').onclick=()=>{saveSettingsFromHome();const parsed=parseOrders(state.draft);if(!parsed.stops.length)return toast('No valid Singapore postal codes found');if(parsed.stops.length>50)return toast('Maximum 50 unique stops');const r={id:id(),created:new Date().toISOString(),title:'Singapore Delivery',...state.settings,stops:parsed.stops};state.routes.unshift(r);state.current=r.id;selectedStop=0;persist();navigate('route');toast('Route created in input order. Road optimization not available yet.')};
}
function googleNav(s){window.open('https://www.google.com/maps/search/?api=1&query='+encodeURIComponent(s.address||'Singapore '+s.postal),'_blank','noopener,noreferrer')}
function bindRoute(){const r=active();$('plan')?.addEventListener('click',()=>navigate('home'));if(!r)return;
 document.querySelectorAll('[data-move]').forEach(b=>b.onclick=()=>{const i=Number(b.dataset.move),j=i+Number(b.dataset.dir);if(j<0||j>=r.stops.length)return;[r.stops[i],r.stops[j]]=[r.stops[j],r.stops[i]];persist();render()});
 document.querySelectorAll('[data-openstop]').forEach(b=>b.onclick=()=>{selectedStop=Number(b.dataset.openstop);navigate('delivery')});
}
function bindDelivery(){const r=active();$('plan')?.addEventListener('click',()=>navigate('home'));if(!r||!r.stops.length)return;const s=r.stops[selectedStop];
 $('next').onclick=()=>{selectedStop++;render()};$('prev').onclick=()=>{selectedStop--;render()};$('nav').onclick=()=>googleNav(s);
 $('message').onclick=()=>{if(!s.phone)return toast('No customer phone number saved');const phone=s.phone.replace(/\D/g,'');window.open('https://wa.me/'+(phone.startsWith('65')?phone:'65'+phone)+'?text='+encodeURIComponent('Hello, your delivery is on the way.'),'_blank','noopener,noreferrer')};
 document.querySelectorAll('[data-status]').forEach(b=>b.onclick=()=>{s.status=b.dataset.status;s.deliveredAt=s.status==='Delivered'?new Date().toISOString():null;persist();render();toast('Stop updated: '+s.status)});
 document.querySelectorAll('[data-proof]').forEach(b=>b.onclick=()=>{proofType=b.dataset.proof;$('proofFile').click()});
}
function bindMap(){const r=active();if(!r)return;document.querySelectorAll('[data-mapstop]').forEach(b=>b.onclick=()=>googleNav(r.stops[Number(b.dataset.mapstop)]))}
function downloadBackup(){const blob=new Blob([JSON.stringify({version:2,...state},null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='SG-Delivery-Backup-'+today()+'.json';document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1500)}
function bindHistory(){$('backup').onclick=downloadBackup;$('restore').onclick=()=>$('restoreFile').click();document.querySelectorAll('[data-history]').forEach(b=>b.onclick=()=>{selectedHistory=selectedHistory===b.dataset.history?null:b.dataset.history;render()})}
$('restoreFile').addEventListener('change',async e=>{const file=e.target.files[0];if(!file)return;try{const s=JSON.parse(await file.text());if(!Array.isArray(s.routes)||s.routes.some(r=>!Array.isArray(r.stops)))throw Error('Invalid backup');if(!confirm('Replace all existing routes and history with this backup?'))return;state={...defaults(),...s,settings:{...defaults().settings,...s.settings}};persist();render();toast('Backup restored')}catch(err){toast('Restore failed: '+err.message)}e.target.value=''});
$('proofFile').addEventListener('change',async e=>{const file=e.target.files[0],r=active(),s=r?.stops[selectedStop];if(!file||!s)return;if(!file.type.startsWith('image/'))return toast('Choose an image');try{const img=await createImageBitmap(file),canvas=document.createElement('canvas'),scale=Math.min(1,900/Math.max(img.width,img.height));canvas.width=Math.round(img.width*scale);canvas.height=Math.round(img.height*scale);canvas.getContext('2d').drawImage(img,0,0,canvas.width,canvas.height);const output=canvas.toDataURL('image/jpeg',.65);if(output.length>1100000)throw Error('Image too large');const field=proofType==='payment'?'proofPayment':'proofDelivery',old=s[field];s[field]=output;if(!persist()){s[field]=old;return}render();toast('Proof photo saved locally')}catch(err){toast('Unable to save photo: '+err.message)}e.target.value=''});
function render(){$('screen').innerHTML=tab==='home'?home():tab==='route'?routeView():tab==='delivery'?deliveryView():tab==='map'?mapView():historyView();if(tab==='home')bindHome();if(tab==='route')bindRoute();if(tab==='delivery')bindDelivery();if(tab==='map')bindMap();if(tab==='history')bindHistory()}
render();
if('serviceWorker'in navigator)navigator.serviceWorker.register('./sw.js').catch(()=>{});
