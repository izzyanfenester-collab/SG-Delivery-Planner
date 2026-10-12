'use strict';
/* Browser port of Planner.kt's directed nearest-neighbour + 2-opt/swap algorithm.
   Road matrix is obtained from a live OSRM-compatible endpoint, not straight-line distance.
   If a browser blocks the routing API, the app must NOT present an unoptimized route as optimized. */
const RoutingCore=(()=>{
const DEPOT={lat:1.4464,lon:103.7696,postal:'738203',address:'Woodlands Checkpoint'};
function tourCost(order,costs){let last=0,total=0;for(const i of order){total+=costs[last][i];last=i}return total+costs[last][0]}
function optimize(costs){
 const n=costs.length;if(n<2||costs.some(row=>row.length!==n||row.some(v=>!Number.isFinite(v)||v<0)))throw Error('Invalid road matrix');
 const remaining=new Set(Array.from({length:n-1},(_,i)=>i+1)),order=[];let last=0;
 while(remaining.size){let next=null;for(const i of remaining)if(next===null||costs[last][i]<costs[last][next])next=i;order.push(next);remaining.delete(next);last=next}
 for(let pass=0;pass<12;pass++){let best=tourCost(order,costs),candidate=null;
  for(let i=0;i<order.length;i++)for(let j=i+1;j<order.length;j++){
   const reversed=order.slice();reversed.splice(i,j-i+1,...order.slice(i,j+1).reverse());
   const swapped=order.slice();[swapped[i],swapped[j]]=[swapped[j],swapped[i]];
   for(const attempt of [reversed,swapped]){const c=tourCost(attempt,costs);if(c<best-.01){best=c;candidate=attempt}}
  }if(!candidate)break;order.splice(0,order.length,...candidate)
 }return order;
}
function trafficBuffer(km,seconds,mode='Normal Traffic'){
 const speed=seconds>0?km/(seconds/3600):0;
 const fraction=speed<30?.24:speed<50?.16:.10;
 const factor=mode==='Light Traffic'?.6:mode==='Heavy Traffic'?1.8:1;
 return seconds<=0?0:(seconds*fraction+Math.min(km,8)*12)*factor;
}
async function resolvePostal(postal){
 const url='https://www.onemap.gov.sg/api/common/elastic/search?searchVal='+encodeURIComponent(postal)+'&returnGeom=Y&getAddrDetails=Y';
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);
 try{
  const response=await fetch(url,{signal:controller.signal,headers:{Accept:'application/json'}});
  if(!response.ok)throw Error('OneMap HTTP '+response.status);
  const json=await response.json();
  const result=(json.results||[]).find(row=>row.POSTAL===postal);
  if(!result)throw Error('Postal code '+postal+' was not found in OneMap');
  const lat=Number(result.LATITUDE),lon=Number(result.LONGITUDE);
  if(!(lat>=1.1&&lat<=1.6&&lon>=103.5&&lon<=104.1))throw Error('Invalid Singapore coordinates for '+postal);
  return {postal,lat,lon,address:result.ADDRESS||'Singapore '+postal,block:result.BLK_NO||'',road:result.ROAD_NAME||''};
 }catch(e){throw Error('Could not look up '+postal+' in OneMap: '+e.message)}finally{clearTimeout(timer)}
}
async function matrix(places,endpoint='https://router.project-osrm.org'){
 const coords=places.map(p=>p.lon+','+p.lat).join(';');
 const url=endpoint.replace(/\/$/,'')+'/table/v1/driving/'+coords+'?annotations=duration,distance';
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),45000);
 try{
  const response=await fetch(url,{signal:controller.signal});
  if(!response.ok)throw Error('Road matrix HTTP '+response.status);
  const json=await response.json();if(json.code!=='Ok')throw Error(json.message||json.code||'Invalid routing response');
  const durations=json.durations,distances=json.distances,n=places.length;
  if(!Array.isArray(durations)||durations.length!==n||!Array.isArray(distances)||distances.length!==n||
   durations.some(row=>!Array.isArray(row)||row.length!==n||row.some(v=>typeof v!=='number'||!Number.isFinite(v)||v<0))||
   distances.some(row=>!Array.isArray(row)||row.length!==n||row.some(v=>typeof v!=='number'||!Number.isFinite(v)||v<0)))
   throw Error('Road service returned an incomplete matrix');
  return {durations,distances};
 }catch(e){throw Error('Driving-route service unavailable: '+e.message)}finally{clearTimeout(timer)}
}
async function routeGeometry(places,endpoint='https://router.project-osrm.org'){
 const coords=places.map(p=>p.lon+','+p.lat).join(';');
 const url=endpoint.replace(/\/$/,'')+'/route/v1/driving/'+coords+'?overview=full&geometries=geojson&steps=false';
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),45000);
 try{const response=await fetch(url,{signal:controller.signal});if(!response.ok)throw Error('HTTP '+response.status);
 const json=await response.json(),line=json.routes?.[0]?.geometry?.coordinates;
 if(json.code!=='Ok'||!Array.isArray(line)||line.length<2)throw Error('Route geometry unavailable');
 return line.map(pair=>[pair[1],pair[0]]).filter(p=>p.every(Number.isFinite));
 }finally{clearTimeout(timer)}
}
async function plan(postals,{onProgress=()=>{},start=DEPOT,minutes=8,date,time='10:00',mode='Normal Traffic'}={}){
 const unique=[...new Set(postals)];if(!unique.length||postals.length>50)throw Error('Enter 1–50 orders');
 if(!Number.isFinite(start.lat)||!Number.isFinite(start.lon))throw Error('Start location coordinates unavailable');
 const places=[start];
 for(let i=0;i<unique.length;i++){onProgress('OneMap '+(i+1)+' / '+unique.length);places.push(await resolvePostal(unique[i]))}
 onProgress('Calculating road driving matrix…');
 const {durations,distances}=await matrix(places);
 const order=optimize(durations);
 const startDate=new Date((date||new Date().toLocaleDateString('en-CA'))+'T'+time+':00+08:00');
 if(Number.isNaN(startDate.getTime()))throw Error('Invalid date or time');
 let now=startDate.getTime(),totalMeters=0,previous=0;
 const schedule=order.map(i=>{
  const seconds=durations[previous][i],km=distances[previous][i]/1000;
  now+=(seconds+trafficBuffer(km,seconds,mode))*1000;const arrival=new Date(now).toISOString();
  now+=minutes*60000;totalMeters+=distances[previous][i];previous=i;
  return {postal:places[i].postal,place:places[i],arrival,leave:new Date(now).toISOString(),legKm:km,legSeconds:seconds};
 });
 const returnSeconds=durations[previous][0],returnKm=distances[previous][0]/1000;
 now+=(returnSeconds+trafficBuffer(returnKm,returnSeconds,mode))*1000;totalMeters+=distances[previous][0];
 let geometry=null;
 try{onProgress('Loading route map…');geometry=await routeGeometry([places[0],...order.map(i=>places[i]),places[0]])}catch(e){onProgress('Route map geometry unavailable; stop pins remain available')}
 return {schedule,returnTime:new Date(now).toISOString(),totalKm:totalMeters/1000,mode,optimized:true,geometry};
}
return {DEPOT,optimize,tourCost,trafficBuffer,resolvePostal,matrix,routeGeometry,plan};
})();
