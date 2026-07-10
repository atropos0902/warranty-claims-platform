// Fifty users for thirty seconds: 70% open a claim, 20% list, 10% file. Prints p50, p95 and p99 per call.
// The NORTH group has to be on the platform.
const base = process.env.CLAIMS_URL ?? 'http://localhost:8081';
const users = Number(process.env.USERS ?? 50);
const seconds = Number(process.env.DURATION ?? 30);

const token = async (user, groups) =>
  (await (await fetch(`${base}/dev/token`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ user, groups }) })).json()).token;
const dealer = await token('dealer', ['DEALER', 'GROUP_NORTH']);
const auth = { authorization: `Bearer ${dealer}`, 'content-type': 'application/json' };
const known = (await (await fetch(`${base}/api/v1/claims`, { headers: auth })).json()).map((c) => c.claimNo);

const times = { open: [], list: [], file: [] };
let failed = 0;
const body = JSON.stringify({
  dealerCode: 'D101', modelCode: 'T40', serialNo: 'SN-L', claimType: 'S', purchaseDate: '2017-01-31', failureDate: '2017-06-30',
  meterHours: 12.5, repairCode: 'HYD-PUMP', laborHours: 6, travelMiles: 10, notes: 'load', lines: [{ partNo: 'A', qty: 1, unitCost: 10.5 }],
});

async function timed(name, path, init) {
  const start = performance.now();
  const r = await fetch(base + path, init);
  await r.arrayBuffer();
  times[name].push(performance.now() - start);
  if (!r.ok) failed++;
}

const end = Date.now() + seconds * 1000;
await Promise.all(
  Array.from({ length: users }, async () => {
    while (Date.now() < end) {
      const roll = Math.random();
      if (roll < 0.7) await timed('open', `/api/v1/claims/${known[Math.floor(Math.random() * known.length)]}`, { headers: auth });
      else if (roll < 0.9) await timed('list', '/api/v1/claims?status=SUBMITTED', { headers: auth });
      else await timed('file', '/api/v1/claims', { method: 'POST', headers: { ...auth, 'idempotency-key': crypto.randomUUID() }, body });
      await new Promise((r) => setTimeout(r, 100));
    }
  }),
);
for (const [name, t] of Object.entries(times)) {
  t.sort((a, b) => a - b);
  const p = (q) => t[Math.min(t.length - 1, Math.floor(t.length * q))].toFixed(0);
  console.log(`${name}: ${t.length} calls, p50 ${p(0.5)} ms, p95 ${p(0.95)} ms, p99 ${p(0.99)} ms`);
}
console.log(`failed: ${failed}`);
