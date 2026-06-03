import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api/client';
import { is, type Person } from '../auth/session';
import { Alert, Heading, money, Status } from '../ui/parts';

export function ClaimList({ person }: { person: Person }) {
  const [status, setStatus] = useState(is(person, 'ADJUSTER') ? 'SUBMITTED' : '');
  const claims = useQuery({ queryKey: ['claims', status], queryFn: () => api.list(status || undefined) });

  return (
    <>
      <Heading>Claims</Heading>
      <div className="toolbar">
        <label htmlFor="status">Show</label>
        <select id="status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All</option>
          <option value="SUBMITTED">Waiting</option>
          <option value="APPROVED">Approved</option>
          <option value="DENIED">Denied</option>
        </select>
        {is(person, 'DEALER') && (
          <Link className="button primary" to="/claims/new">
            File a claim
          </Link>
        )}
      </div>
      {claims.error && <Alert>{claims.error.message}</Alert>}
      {claims.data?.length === 0 && <p>No claims here.</p>}
      {claims.data && claims.data.length > 0 && (
        <div className="scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">Claim</th>
                <th scope="col">Dealer</th>
                <th scope="col">Model</th>
                <th scope="col">Repair</th>
                <th scope="col">Status</th>
                <th scope="col" className="number">Total</th>
              </tr>
            </thead>
            <tbody>
              {claims.data.map((c) => (
                <tr key={c.claimNo}>
                  <td><Link to={`/claims/${c.claimNo}`}>{c.claimNo}</Link></td>
                  <td>{c.dealerName}</td>
                  <td>{c.modelCode}</td>
                  <td>{c.repairCode}</td>
                  <td><Status value={c.status} /></td>
                  <td className="number">{c.totalPay === null ? 'not priced' : money(c.totalPay)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
