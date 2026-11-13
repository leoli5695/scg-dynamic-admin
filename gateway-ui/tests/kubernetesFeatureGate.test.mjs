import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = path => fs.readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');

test('disabled Kubernetes never loads clusters and selects safe fallbacks', () => {
  const instances = read('src/features/instances/InstancesListPage.tsx');
  assert.match(instances, /immediate: kubernetesEnabled/);
  assert.match(instances, /kubernetesEnabled \? \(clusters\.data\?\.length \?\? 0\) : 0/);
  assert.match(instances, /kubernetesEnabled && \(\s*<Button onClick=\{\(\) => navigate\('\/kubernetes'\)\}/);

  const create = read('src/features/instances/InstanceCreatePage.tsx');
  assert.match(create, /immediate: kubernetesEnabled/);
  assert.match(create, /settings\?\.kubernetesEnabled === false \? 'external' : deployMode/);
  assert.match(create, /value=\{effectiveDeployMode\}/);

  const logs = read('src/features/access-log/components/LogViewerTab.tsx');
  assert.match(logs, /settingsReady && settings\?\.kubernetesEnabled === true && deployMode === 'K8S'/);
  assert.match(logs, /immediate: isK8s/);
  assert.match(logs, /!settingsReady \|\| \(isK8s && !selectedPod\)/);
});
