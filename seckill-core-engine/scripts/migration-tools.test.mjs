import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const script=name=>fileURLToPath(new URL(name,import.meta.url));
test('SQL plan validates before cutover, preserves source tables, and refuses overwriting a plan',()=>{
 const directory=mkdtempSync(join(tmpdir(),'seckill-plan-'));
 try {
  const output=join(directory,'migration.sql');
  const generated=spawnSync(process.execPath,[script('plan-order-routing-v2.mjs'),output],{encoding:'utf8'});
  assert.equal(generated.status,0,generated.stderr);
  const sql=readFileSync(output,'utf8');
  assert.ok(sql.indexOf('CALL seckill_db_0.verify_order_route_v2();')<sql.indexOf('RENAME TABLE'));
  assert.ok(sql.includes("SIGNAL SQLSTATE '45000'"));assert.ok(!/^INSERT IGNORE/im.test(sql));
  assert.ok(!sql.includes('DROP TABLE'));assert.ok(sql.includes('order_v1_backup_15'));
  writeFileSync(output,'operator-reviewed plan');
  assert.notEqual(spawnSync(process.execPath,[script('plan-order-routing-v2.mjs'),output]).status,0);
  assert.equal(readFileSync(output,'utf8'),'operator-reviewed plan');
 } finally {rmSync(directory,{recursive:true,force:true});}
});
test('stock migration defaults to a read-free, write-free plan',()=>{
 const result=spawnSync(process.execPath,[script('migrate-stock-hash-tags.mjs'),'42','8'],
  {encoding:'utf8',env:{...process.env,REDIS_CLI:'deliberately-missing-redis-cli'}});
 assert.equal(result.status,0,result.stderr);
 const plan=JSON.parse(result.stdout);assert.equal(plan.offlineRequired,true);assert.equal(plan.copies.length,10);
 assert.ok(plan.copies.every(([oldKey,newKey])=>oldKey!==newKey && newKey.includes('{42}')));
});
test('invalid stock migration arguments cannot enter copy mode',()=>{
 for(const args of [['42','0','--copy-offline'],['42','129','--copy-offline'],['42','8','--force']]) {
  const result=spawnSync(process.execPath,[script('migrate-stock-hash-tags.mjs'),...args],{encoding:'utf8'});
  assert.notEqual(result.status,0);assert.ok(!result.stdout.includes('Verified'));
 }
});
