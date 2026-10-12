'use strict';
/* Minimal portable ZIP (STORE method, CRC32) for offline Safari backup.
   Deliberately accepts only archives created in this format; no dependencies. */
const ZipCore=(()=>{
 const encoder=new TextEncoder(),decoder=new TextDecoder('utf-8',{fatal:true});
 const MAX=60*1024*1024;
 const table=new Uint32Array(256);
 for(let n=0;n<256;n++){let c=n;for(let k=0;k<8;k++)c=c&1?0xedb88320^(c>>>1):c>>>1;table[n]=c>>>0}
 function crc32(bytes){let crc=0xffffffff;for(const b of bytes)crc=table[(crc^b)&255]^(crc>>>8);return (crc^0xffffffff)>>>0}
 function concat(chunks,total){const output=new Uint8Array(total);let offset=0;for(const part of chunks){output.set(part,offset);offset+=part.length}return output}
 function write16(view,at,v){view.setUint16(at,v,true)}
 function write32(view,at,v){view.setUint32(at,v>>>0,true)}
 function pack(entries){
  if(!Array.isArray(entries)||entries.length<1||entries.length>100)throw Error('Invalid ZIP file count');
  const local=[],central=[];let offset=0,centralSize=0;
  const names=new Set();
  for(const [name,content] of entries){
   if(typeof name!=='string'||!(/^[a-zA-Z0-9._/-]+$/.test(name))||name.includes('..')||names.has(name))throw Error('Invalid ZIP entry name');
   names.add(name);
   const filename=encoder.encode(name),data=typeof content==='string'?encoder.encode(content):content;
   if(!(data instanceof Uint8Array)||data.length>MAX||offset+data.length>MAX)throw Error('ZIP backup exceeds 60 MB');
   const crc=crc32(data),header=new Uint8Array(30+filename.length),h=new DataView(header.buffer);
   write32(h,0,0x04034b50);write16(h,4,20);write16(h,6,0x800);write16(h,8,0);
   write32(h,14,crc);write32(h,18,data.length);write32(h,22,data.length);
   write16(h,26,filename.length);header.set(filename,30);
   local.push(header,data);
   const entry=new Uint8Array(46+filename.length),v=new DataView(entry.buffer);
   write32(v,0,0x02014b50);write16(v,4,20);write16(v,6,20);write16(v,8,0x800);
   write16(v,10,0);write32(v,16,crc);write32(v,20,data.length);write32(v,24,data.length);
   write16(v,28,filename.length);write32(v,42,offset);entry.set(filename,46);
   central.push(entry);centralSize+=entry.length;offset+=header.length+data.length;
  }
  const end=new Uint8Array(22),e=new DataView(end.buffer);
  write32(e,0,0x06054b50);write16(e,8,entries.length);write16(e,10,entries.length);
  write32(e,12,centralSize);write32(e,16,offset);
  return concat([...local,...central,end],offset+centralSize+22);
 }
 function unpack(input){
  const bytes=input instanceof Uint8Array?input:new Uint8Array(input);
  if(bytes.length<22||bytes.length>MAX)throw Error('ZIP file is missing or too large');
  const v=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
  let end=-1;
  for(let i=bytes.length-22;i>=Math.max(0,bytes.length-65557);i--)if(v.getUint32(i,true)===0x06054b50&&i+22+v.getUint16(i+20,true)===bytes.length){end=i;break}
  if(end<0)throw Error('Invalid ZIP archive');
  const count=v.getUint16(end+10,true),start=v.getUint32(end+16,true),size=v.getUint32(end+12,true);
  if(count<1||count>100||start+size!==end||v.getUint16(end+8,true)!==count)throw Error('Invalid ZIP directory');
  const files=new Map();let pos=start,total=0;
  for(let i=0;i<count;i++){
   if(pos+46>end||v.getUint32(pos,true)!==0x02014b50)throw Error('Invalid ZIP entry');
   const flags=v.getUint16(pos+8,true),method=v.getUint16(pos+10,true);
   const crc=v.getUint32(pos+16,true),compressed=v.getUint32(pos+20,true),raw=v.getUint32(pos+24,true);
   const n=v.getUint16(pos+28,true),extra=v.getUint16(pos+30,true),comment=v.getUint16(pos+32,true);
   const localAt=v.getUint32(pos+42,true);
   if(method!==0||compressed!==raw||(flags&0x1)||!(flags&0x800)||raw>MAX||total+raw>MAX)throw Error('Unsupported or oversized ZIP entry');
   if(pos+46+n+extra+comment>end||localAt+30>start)throw Error('ZIP entry outside directory');
   const name=decoder.decode(bytes.subarray(pos+46,pos+46+n));
   if(!/^[a-zA-Z0-9._/-]+$/.test(name)||name.includes('..')||files.has(name))throw Error('Unsafe or duplicate ZIP entry');
   if(v.getUint32(localAt,true)!==0x04034b50)throw Error('ZIP local header missing');
   const dataAt=localAt+30+v.getUint16(localAt+26,true)+v.getUint16(localAt+28,true);
   if(dataAt+raw>start)throw Error('ZIP data truncated');
   const data=bytes.subarray(dataAt,dataAt+raw);
   if(crc32(data)!==crc)throw Error('ZIP checksum mismatch');
   files.set(name,data);total+=raw;pos+=46+n+extra+comment;
  }
  if(pos!==end)throw Error('Unexpected ZIP directory data');
  return files;
 }
 function text(bytes){return decoder.decode(bytes)}
 return {pack,unpack,text,crc32};
})();