import assert from 'node:assert/strict';

const base = 'https://completely-pleasing-flamingo.edgecompute.app/api/v1/files';
async function api(path = '', method = 'GET', body) {
  const response = await fetch(base + path, {
    method,
    headers: body ? { 'content-type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const result = await response.json();
  assert.ok(response.ok && result.success, `${method}: HTTP ${response.status} ${result.message}`);
  return result.data;
}

const original = `preview-check-${Date.now()} space.png`;
const renamed = original.replace(' space', ' renamed');
const cleanup = new Set();
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64');
try {
  const upload = await api('/upload-url', 'POST', { name: original, content_type: 'image/png', size: png.length });
  assert.notEqual(new URL(upload.upload_url).host, new URL(base).host);
  cleanup.add(original);
  const put = await fetch(upload.upload_url, { method: 'PUT', headers: upload.headers, body: png });
  assert.ok(put.ok, `Direct PUT: ${put.status}`);
  console.log('PASS direct upload');
  const listed = (await api()).find(file => file.name === original);
  assert.ok(listed);
  const thumbnail = await fetch(listed.download_url);
  assert.equal(thumbnail.status, 200);
  assert.deepEqual(Buffer.from(await thumbnail.arrayBuffer()), png);
  console.log('PASS list and thumbnail bytes');
  const fresh = await api('/' + encodeURIComponent(original));
  const preview = await fetch(fresh.download_url);
  assert.equal(preview.status, 200);
  assert.deepEqual(Buffer.from(await preview.arrayBuffer()), png);
  console.log('PASS fresh preview/share URL');
  cleanup.add(renamed);
  await api('/rename', 'POST', { old_name: original, new_name: renamed });
  const files = await api();
  assert.ok(files.some(file => file.name === renamed));
  assert.ok(!files.some(file => file.name === original));
  const moved = await api('/' + encodeURIComponent(renamed));
  assert.deepEqual(Buffer.from(await (await fetch(moved.download_url)).arrayBuffer()), png);
  console.log('PASS rename preserves image');
} finally {
  for (const name of cleanup) await api('/' + encodeURIComponent(name), 'DELETE');
  const remaining = await api();
  assert.ok(!remaining.some(file => cleanup.has(file.name)));
  console.log('PASS delete and cleanup');
}
