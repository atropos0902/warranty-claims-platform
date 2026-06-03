import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router';
import { api } from '../api/client';
import type { Claim } from '../api/types';
import { is, type Person } from '../auth/session';
import { Alert, Heading, money, Status } from '../ui/parts';
import { EditClaim } from './EditClaim';
import { Review } from './Review';

export function ClaimPage({ person }: { person: Person }) {
  const { claimNo = '' } = useParams();
  const claim = useQuery({ queryKey: ['claim', claimNo], queryFn: () => api.claim(claimNo) });
  const groups = useQuery({ queryKey: ['groups'], queryFn: api.groups });
  const [editing, setEditing] = useState(false);

  if (claim.error) return <Alert>{claim.error.message}</Alert>;
  if (!claim.data || !groups.data) return <p role="status">Loading</p>;
  const c = claim.data;
  const onPlatform = groups.data.some((g) => g.groupCode === c.groupCode && g.owner === 'PLATFORM');
  const open = c.status === 'SUBMITTED' && onPlatform;

  return (
    <>
      <Heading>
        Claim {c.claimNo} <Status value={c.status} />
      </Heading>
      {!onPlatform && <p className="note">This dealer group is on the old system, so the claim is read-only here.</p>}
      {editing ? (
        <EditClaim claim={c} onDone={() => setEditing(false)} />
      ) : (
        <div className="columns">
          <div>
            <Facts c={c} />
            <PriceTable c={c} />
            {open && is(person, 'DEALER') && <button onClick={() => setEditing(true)}>Edit claim</button>}
          </div>
          {is(person, 'ADJUSTER') && <Review claim={c} open={open} />}
        </div>
      )}
    </>
  );
}

function Facts({ c }: { c: Claim }) {
  const rows: [string, string][] = [
    ['Dealer', c.dealerCode],
    ['Model', `${c.modelCode}, serial ${c.serialNo}`],
    ['Purchased', c.purchaseDate],
    ['Failed', c.failureDate],
    ['Meter', c.meterHours === null ? 'not given' : `${c.meterHours} hours`],
    ['Repair', c.repairCode],
    ['Labor', `${c.laborHours} hours`],
    ['Parts', c.lines.map((l) => `${l.qty} × ${l.partNo}`).join(', ') || 'none'],
  ];
  return (
    <section aria-labelledby="facts">
      <h2 id="facts">Repair</h2>
      <dl>
        {rows.map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
      <p className="notes">{c.notes ?? 'No repair notes.'}</p>
    </section>
  );
}

function PriceTable({ c }: { c: Claim }) {
  if (!c.price) return <p className="note">This claim has not been priced yet.</p>;
  const p = c.price;
  return (
    <section aria-labelledby="price">
      <h2 id="price">Payout</h2>
      <table className="price">
        <tbody>
          <tr><th scope="row">Labor</th><td>{money(p.laborPay)}</td></tr>
          <tr><th scope="row">Parts and handling</th><td>{money(p.partsPay)}</td></tr>
          <tr><th scope="row">Travel</th><td>{money(p.travelPay)}</td></tr>
          <tr><th scope="row">Coverage</th><td>{p.coveragePct}%</td></tr>
          <tr className="total"><th scope="row">Total</th><td>{money(p.totalPay)}</td></tr>
        </tbody>
      </table>
      {p.steps.length > 0 && (
        <ul className="steps">
          {p.steps.map((s) => (
            <li key={s.rule}>{s.detail}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
