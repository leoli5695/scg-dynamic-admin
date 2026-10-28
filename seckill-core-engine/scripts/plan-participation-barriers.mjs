// Reads a MySQL batch export: seckill_id<TAB>user_id. Generates RESP only; never connects to Redis.
import {createReadStream,createWriteStream} from 'node:fs';
import {createInterface} from 'node:readline';
import {once} from 'node:events';
const [input,output,format='legacy']=process.argv.slice(2);
if(!input||!output||!['legacy','tagged'].includes(format))
 throw new Error('Usage: node plan-participation-barriers.mjs <orders.tsv> <output.resp> [legacy|tagged]');
function ids(line){
 const parts=line.split('\t');
 if(parts.length!==2||parts.some(id=>!/^\d+$/.test(id)||BigInt(id)<=0n||BigInt(id)>9223372036854775807n))
  throw new Error('Expected two positive BIGINT columns without a header: seckill_id, user_id');
 return parts.map(id=>BigInt(id).toString());
}
// Validate before creating an output that an operator might apply.
for await(const line of createInterface({input:createReadStream(input),crlfDelay:Infinity}))if(line.trim())ids(line);
const stream=createWriteStream(output,{flags:'wx'});await once(stream,'open');
let count=0;
for await(const line of createInterface({input:createReadStream(input),crlfDelay:Infinity})){
 if(!line.trim())continue;
 const [activity,user]=ids(line);
 const command=['HSET','seckill:released:'+(format==='tagged'?'{'+activity+'}':activity),'__closed_user:'+user,'1'];
 const resp='*4\r\n'+command.map(value=>'$'+Buffer.byteLength(value)+'\r\n'+value+'\r\n').join('');
 if(!stream.write(resp))await once(stream,'drain');
 count++;
}
stream.end();await once(stream,'finish');
console.log(`Generated ${count} participation barriers. No inventory was changed and no Redis connection was made.`);
