'use strict';
/* Browser parity with DeliveryState.kt: immutable completed timestamps,
   delivery review flow, and conservative revised ETAs (planning only). */
const DeliveryCore=(()=>{
const REASONS=['Customer not home','No answer','Reschedule','Payment issue','Access issue','Joy buyer','Other'];
function counts(stops){
 const total=stops.length,delivered=stops.filter(s=>s.status==='Delivered').length,
 hold=stops.filter(s=>s.status==='Hold').length,skipped=stops.filter(s=>s.status==='Skipped').length;
 return {total,delivered,hold,skipped,pending:total-delivered-hold-skipped,
  successRate:total?Math.round(delivered/total*1000)/10:0};
}
function nextUnreviewed(stops,current){
 let i=stops.findIndex((s,j)=>j>current&&!s.reviewedAt);
 if(i<0)i=stops.findIndex(s=>!s.reviewedAt);
 return i;
}
function update(stop,status,now,reason='',note=''){
 if(!['Delivered','Hold','Skipped'].includes(status))throw Error('Invalid delivery status');
 if(stop.status==='Delivered')throw Error('Delivered records cannot be changed');
 if(status==='Hold'){
  if(!REASONS.includes(reason))throw Error('Choose a valid hold reason');
  if(reason==='Other'&&!note.trim())throw Error('A note is required for Other');
 }
 const result={...stop,status,reviewedAt:now,
  holdReason:status==='Hold'?reason:null,
  holdNote:status==='Hold'&&reason==='Other'?note.trim().slice(0,160):null};
 if(status==='Delivered')result.deliveredAt=stop.deliveredAt||now;
 return result;
}
function recalculate(stops,returnTime,minutes=8,mode='Normal Traffic'){
 const anchor=stops.reduce((max,s)=>s.status==='Delivered'&&s.deliveredAt?Math.max(max,Date.parse(s.deliveredAt)||0):max,0);
 if(!anchor)return {stops,returnTime};
 let clock=anchor;
 const revised=stops.map(s=>{
  if(s.status!=='Pending'||s.reviewedAt)return s;
  const seconds=Number(s.legSeconds)||0,km=Number(s.legKm)||0;
  clock+=(seconds+((typeof RoutingCore!=='undefined')?RoutingCore.trafficBuffer(km,seconds,mode):seconds*.2))*1000;
  const arrival=new Date(clock).toISOString();clock+=minutes*60000;
  return {...s,etaArrival:arrival,etaLeave:new Date(clock).toISOString()};
 });
 // Preserve original return time: actual return leg isn't stored separately.
 return {stops:revised,returnTime};
}
return {REASONS,counts,nextUnreviewed,update,recalculate};
})();