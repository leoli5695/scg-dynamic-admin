// Generates SQL only. Stop ALL writers/consumers/schedulers and back up before applying.
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
const output = process.argv[2];
if (!output) throw new Error('Usage: node plan-order-routing-v2.mjs <output.sql>');
const statements = [
  '-- OFFLINE ONLY: back up, stop all writers, drain MQ and batch writes before running.',
  '-- Preserve every column and primary key. Any duplicate key aborts; never use INSERT IGNORE.',
  '-- Requires identical order schemas and permission to create a validation procedure.',
  "SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION';",
  'DELIMITER $$',
  'CREATE PROCEDURE seckill_db_0.verify_order_route_v2() BEGIN',
];
// Check source placement before copying, and check counts before cutover.
for (let db=0;db<8;db++) {
  for (let table=0;table<16;table++) {
    statements.push(`IF EXISTS(SELECT 1 FROM seckill_db_${db}.order_${table} WHERE MOD(user_id,8) <> ${db}) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Wrong source database placement'; END IF;`);
  }
  const before=Array.from({length:16},(_,i)=>`(SELECT COUNT(*) FROM seckill_db_${db}.order_${i})`).join('+');
  const after=Array.from({length:16},(_,i)=>`(SELECT COUNT(*) FROM seckill_db_${db}.order_v2_${i})`).join('+');
  statements.push(`IF (${before}) <> (${after}) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Migration row count mismatch'; END IF;`);
}
statements.push('END$$','DELIMITER ;');
for(let db=0;db<8;db++) {
  for(let table=0;table<16;table++) {
    // Missing empty physical tables in older init scripts are provisioned with the canonical schema.
    statements.push(`CREATE TABLE IF NOT EXISTS seckill_db_${db}.order_${table} LIKE seckill_db_0.order_0;`);
    statements.push(`CREATE TABLE seckill_db_${db}.order_v2_${table} LIKE seckill_db_${db}.order_${table};`);
  }
  for(let target=0;target<16;target++)for(let source=0;source<16;source++) {
    statements.push(`INSERT INTO seckill_db_${db}.order_v2_${target} SELECT * FROM seckill_db_${db}.order_${source} WHERE MOD(user_id DIV 8,16)=${target};`);
  }
}
statements.push('CALL seckill_db_0.verify_order_route_v2();');
// Single RENAME statement across schemas on the same MySQL server; retain original tables.
const renames=[];
for(let db=0;db<8;db++)for(let table=0;table<16;table++) {
  renames.push(`seckill_db_${db}.order_${table} TO seckill_db_${db}.order_v1_backup_${table}`);
  renames.push(`seckill_db_${db}.order_v2_${table} TO seckill_db_${db}.order_${table}`);
}
statements.push('RENAME TABLE\n  '+renames.join(',\n  ')+';','DROP PROCEDURE seckill_db_0.verify_order_route_v2();',
  '-- Keep traffic stopped; switch EVERY instance to SECKILL_ORDER_ROUTING_VERSION=2, verify routing, then reopen.',
  '-- Stop on the FIRST SQL error. Do not use mysql --force. For multiple MySQL hosts, migrate each host separately before a coordinated cutover.');
writeFileSync(resolve(output),statements.join('\n')+'\n',{flag:'wx'});
console.log(`Generated ${resolve(output)}; no database connection or mutation was made.`);
