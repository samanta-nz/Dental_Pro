import assert from 'node:assert/strict';
let handler;
globalThis.Deno = { env: { get: k => k === 'SUPABASE_URL' ? 'https://mock.invalid' : 'mock-service-key' }, serve: fn => { handler = fn; } };
for (const name of ['send-otp', 'verify-otp', 'phone-to-email']) {
  await import(`../${name}/index.ts`);
  assert.equal((await handler(new Request('https://mock.invalid', {method:'POST'}))).status, 410);
}
await import('../delete-account/index.ts');
assert.equal((await handler(new Request('https://mock.invalid', {method:'POST'}))).status, 401);
const calls=[];
globalThis.fetch = async(url,options={}) => {
  calls.push({url,options});
  if(url.endsWith('/auth/v1/user')) return Response.json({id:'mock-owner',role:'authenticated'});
  if(url.includes('/object/list/')) return Response.json(JSON.parse(options.body).offset === 0 ? [{id:'mock-image',name:'image.jpg'}] : []);
  if(url.includes('/storage/v1/object/')) { assert.deepEqual(JSON.parse(options.body).prefixes,['mock-owner/image.jpg']); return Response.json([]); }
  if(url.endsWith('/rpc/delete_my_account')) return Response.json(null);
  if(url.endsWith('/admin/users/mock-owner')) return new Response(null,{status:204});
  throw Error('Unexpected request');
};
assert.equal((await handler(new Request('https://mock.invalid',{method:'POST',headers:{authorization:'Bearer mock-user-token'}}))).status,200);
assert.equal(calls.filter(c=>c.options.method==='DELETE').length,3);
await import('../extract-cheque-data/index.ts');
assert.equal((await handler(new Request('https://mock.invalid',{method:'POST'}))).status,401);
console.log('Google-only endpoints, authenticated deletion, and AI authorization checks passed.');
