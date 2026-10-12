'use strict';
/* Port of CustomerOrders.kt parsing semantics. Each parcel has its own identity.
   Duplicate postal codes represent different deliveries and must be preserved. */
const OrdersCore=(()=>{
const postalRe=/(?<!\d)\d{6}(?!\d)/g;
function normalizePhone(raw){
 const digits=String(raw||'').replace(/\D/g,'');
 if(/^[89]\d{7}$/.test(digits))return '+65'+digits;
 if(/^65[89]\d{7}$/.test(digits))return '+'+digits;
 return '';
}
function label(line){
 const m=line.trimStart().replace(/^[^\p{L}\p{N}]+/u,'').match(/^([^:]+):[ \t]*(.*)$/);
 if(!m)return null;
 const key=m[1].trim().toLowerCase().replace(/\./g,'').replace(/[ \t]+/g,' ');
 const fields={
  'nama':'name','name':'name','customer':'name','customer name':'name',
  'alamat':'address','address':'address',
  'no telefon':'phone','notelefon':'phone','phone':'phone','phone no':'phone','telephone':'phone','whatsapp':'phone','whatsapp no':'phone',
  'total':'price','amount':'price',
  'order':'end','item':'end','tarikh order':'end','date':'end','fb':'end','page':'end'
 };
 return fields[key]?{field:fields[key],value:m[2]}:null;
}
function heading(line){
 if(label(line))return false;
 const clean=line.trimStart().replace(/^[^\p{L}\p{N}]+/u,'');
 return /^ORDER\b|\bINVOICE[ \t]*:/i.test(clean);
}
function splitOrders(raw){
 const blocks=[];let lines=[],seen=new Set();
 const flush=()=>{if(lines.some(l=>l.trim()))blocks.push(lines);lines=[];seen=new Set()};
 for(const original of raw.replace(/\r\n?/g,'\n').split('\n')){
  const header=original.match(/^[ \t]*\[\d{1,4}[\/.-]\d{1,2}[^\]\n]*\][ \t]*[^:\n]+:[ \t]*(.*)$/)||
   original.match(/^[ \t]*\d{1,4}[\/.-]\d{1,2}(?:[\/.-]\d{2,4})?,[ \t]*\d{1,2}:\d{2}[^\n]*? - [^:\n]+:[ \t]*(.*)$/);
  if(header)flush();
  const line=header?header[1]:original;
  const field=label(line)?.field;
  const primary=['name','price','address'].includes(field);
  if((heading(line)&&seen.size>0)||(primary&&seen.has(field)))flush();
  lines.push(line);if(field)seen.add(field);
 }
 flush();return blocks;
}
function parse(raw,makeId=()=>Math.random().toString(36).slice(2)){
 if(!raw?.trim())return {stops:[],duplicates:0,failed:0};
 // Manual lists use one postal code per visit, matching Android parseInput.
 if(/^[\s\d,;]+$/.test(raw)&&/\d{6}/.test(raw)){
  const tokens=raw.match(postalRe)||[],seen=new Set(),stops=[];let duplicates=0;
  for(const postal of tokens){if(seen.has(postal)){duplicates++;continue}seen.add(postal);
   stops.push({id:makeId(),postal,name:'',phone:'',amount:'',payment:'Not specified',address:'',originalMessage:'',status:'Pending',deliveredAt:null,proofDelivery:null,proofPayment:null})}
  return {stops,duplicates,failed:0};
 }
 const blocks=splitOrders(raw),stops=[];let failed=0,duplicates=0;
 // Preserve identical messages if they are distinct WhatsApp orders; each has its own ID.
 for(const lines of blocks){
  const fields=new Map();let current=null,value=[];
  const save=()=>{if(current&&!fields.has(current))fields.set(current,value.join('\n').trim());value=[]};
  for(const line of lines){
   const found=label(line);
   if(found){save();current=found.field;value=[found.value]}
   else if(heading(line)){save();current=null}
   else if(current){value.push(line)}
  }save();
  const address=fields.get('address')||'';
  const searchable=address.replace(/https?:\/\/\S+/gi,' ').replace(/\b\d{1,4}[\/.-]\d{1,2}[\/.-]\d{2,4}\b/g,' ');
  const postal=searchable.match(/(?:\bSingapore|\bS['’]pore|\bSG|\bS)[ \t]*(\d{6})(?!\d)/i)?.[1]||searchable.match(postalRe)?.[0];
  if(!postal){failed++;continue}
  const phoneField=fields.get('phone')||'';
  const phoneRaw=phoneField.match(/wa\.me\/\+?(\d+)/i)?.[1]||phoneField.split('\n').find(Boolean)||'';
  const priceField=fields.get('price')||'';
  const amount=priceField.match(/^(?:\$\s*|SGD\s*)?(\d+(?:\.\d{1,2})?)(?![\d.])/i)?.[1]||'';
  const payment=/\bPAYNOW\b/i.test(priceField)?'PayNow':/\bCOD\b/i.test(priceField)?'Cash On Delivery':'Not specified';
  stops.push({id:makeId(),postal,name:(fields.get('name')||'').split('\n')[0],phone:normalizePhone(phoneRaw),amount,payment,address,
   originalMessage:lines.join('\n').trim(),status:'Pending',deliveredAt:null,proofDelivery:null,proofPayment:null});
 }
 return {stops,duplicates,failed};
}
return {parse,normalizePhone,splitOrders};
})();
