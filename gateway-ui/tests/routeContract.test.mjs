import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { routeContractFixtures } from './routeContractFixtures.mjs';
import { extractMethods, parsePredicateArgs } from '../src/features/routes/utils.ts';

test('backend contract fixtures match the current frontend request builder', () => {
  const fixtures = JSON.parse(readFileSync(new URL('../../gateway-admin/src/test/resources/contracts/routes.json', import.meta.url), 'utf8'));
  assert.deepEqual(JSON.parse(JSON.stringify(routeContractFixtures)), fixtures);
});

test('Method multi-select values round trip as indexed string arguments', () => {
  const args = parsePredicateArgs('Method', ['GET', 'POST']);
  assert.deepEqual(args, { 'methods.0': 'GET', 'methods.1': 'POST' });
  assert.deepEqual(extractMethods(args), ['GET', 'POST']);
});
