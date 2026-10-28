// Offline copy, deliberately never deletes source keys or overwrites different destination data.
// Auth: REDISCLI_AUTH is inherited by redis-cli; never print credentials.
import { spawnSync } from 'node:child_process';
const [activity, countArg, mode] = process.argv.slice(2);
if (!/^\d+$/.test(activity??'') || !/^\d+$/.test(countArg??''))
  throw new Error('Usage: node migrate-stock-hash-tags.mjs <activityId> <shardCount> [--copy-offline]');
const count=Number(countArg);
if(count<1||count>128)throw new Error('shardCount must be 1..128');
if(mode && mode!=='--copy-offline')throw new Error('Unknown mode');
const pairs=[['seckill:bought:'+activity,'seckill:bought:{'+activity+'}'],
  ['seckill:user_shard:'+activity,'seckill:user_shard:{'+activity+'}'],
  ...Array.from({length:count},(_,i)=>[`seckill:stock:${activity}:shard:${i}`,`seckill:stock:{${activity}}:shard:${i}`])];
if(!mode){console.log(JSON.stringify({offlineRequired:true,copies:pairs},null,2));process.exit(0);}
const base=['-c','--raw','-h',process.env.REDIS_HOST??'127.0.0.1','-p',process.env.REDIS_PORT??'6379'];
function command(args,input){
  const result=spawnSync(process.env.REDIS_CLI??'redis-cli',[...base,...args],{input,maxBuffer:256*1024*1024,windowsHide:true});
  if(result.error||result.status!==0)throw new Error('redis-cli command failed: '+args[0]);
  // redis-cli appends one LF to raw output, including binary DUMP payloads.
  const out=result.stdout.subarray(0,-1);
  if(/^(ERR |CROSSSLOT |NOAUTH |WRONGTYPE |BUSYKEY )/.test(out.toString('utf8')))throw new Error('Redis rejected '+args[0]);
  return out;
}
for(const [source,target] of pairs){
  const ttl=Number(command(['PTTL',source]).toString());
  if(ttl===-2)continue;
  const start=Date.now(),dump=command(['DUMP',source]);
  if(command(['EXISTS',target]).toString()==='1'){
    if(!dump.equals(command(['DUMP',target])))throw new Error('Different target data; refusing overwrite: '+target);
  } else {
    const remaining=ttl===-1?0:ttl-(Date.now()-start);
    if(ttl!==-1 && remaining<=0)throw new Error('Source expired while copying; restart offline migration');
    const result=command(['-x','RESTORE',target,String(remaining)],dump).toString();
    if(result!=='OK')throw new Error('Restore not confirmed for '+target);
    if(!dump.equals(command(['DUMP',target])))throw new Error('Copy verification failed: '+target);
  }
  console.log('Verified '+target);
}
console.log('Source keys retained. Keep traffic stopped until ALL instances enable stock-hash-tag-enabled.');
