import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Claim } from '../api/types';
import { EditClaim } from './EditClaim';
import { differences, emptyForm, fromClaim, toRequest } from './form';
import { NewClaim } from './NewClaim';
import { DraftView, Review } from './Review';

const CLAIM: Claim = {
  claimNo: 'P1', groupCode: 'NORTH', dealerCode: 'D101', modelCode: 'T40', serialNo: 'SN-1', claimType: 'S',
  purchaseDate: '2017-01-01', failureDate: '2017-06-01', meterHours: 10, repairCode: 'HYD-PUMP', laborHours: 6,
  travelMiles: null, notes: 'whines', status: 'SUBMITTED', version: 1,
  lines: [{ partNo: 'HP-100', qty: 1, unitCost: 100 }], decisionNote: null,
  price: { coveragePct: 100, laborPay: 501.5, partsPay: 110, travelPay: 0, totalPay: 611.5, steps: [] },
};

type Call = { url: string; method: string; headers: Headers; body: unknown };
let calls: Call[];
let answers: ((c: Call) => Response | undefined)[];
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  calls = [];
  answers = [];
  sessionStorage.clear();
  vi.stubGlobal('fetch', async (r: Request) => {
    const text = await r.text();
    const call = { url: r.url, method: r.method, headers: r.headers, body: text ? JSON.parse(text) : null };
    calls.push(call);
    if (r.url.includes('/reference')) {
      return json(200, { models: ['T40'], repairCodes: [{ repairCode: 'HYD-PUMP', description: 'Hydraulic pump, replace', capHours: 4.25 }] });
    }
    if (r.url.includes('/dealers')) return json(200, [{ dealerCode: 'D101', dealerName: 'Prairie Tractor', groupCode: 'NORTH' }]);
    for (const a of answers) {
      const found = a(call);
      if (found) return found;
    }
    return json(500, {});
  });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function show(ui: React.ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <Routes>
          <Route path="/" element={ui} />
          <Route path="/claims/:no" element={<p>filed</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('form', () => {
  it('keeps every field on the way in and out', () => {
    const { claimNo: _n, groupCode: _g, status: _s, version: _v, price: _p, decisionNote: _d, ...rest } = CLAIM;
    expect(toRequest(fromClaim(CLAIM))).toEqual(rest);
  });

  it('sends blanks as missing and drops empty part lines', () => {
    const r = toRequest({ ...emptyForm(), laborHours: '1' });
    expect(r.meterHours).toBeNull();
    expect(r.lines).toEqual([]);
  });

  it('lists only the fields that differ', () => {
    const mine = { ...fromClaim(CLAIM), laborHours: '5' };
    expect(differences(mine, fromClaim(CLAIM)).map((d) => d.key)).toEqual(['laborHours']);
  });
});

describe('filing', () => {
  it('uses the same key when a failed attempt is repeated', async () => {
    let tries = 0;
    answers.push((c) => (c.method === 'POST' ? (++tries === 1 ? json(503, {}) : json(201, CLAIM)) : undefined));
    show(<NewClaim />);
    const user = userEvent.setup();
    await screen.findByRole('option', { name: 'T40' });
    await screen.findByRole('option', { name: 'Prairie Tractor' });
    await user.selectOptions(screen.getByLabelText('Dealer'), 'D101');
    await user.selectOptions(screen.getByLabelText('Model'), 'T40');
    await user.type(screen.getByLabelText('Serial number'), 'SN-1');
    await user.type(screen.getByLabelText('Purchase date'), '2017-01-01');
    await user.type(screen.getByLabelText('Failure date'), '2017-06-01');
    await user.selectOptions(screen.getByLabelText('Repair'), 'HYD-PUMP');
    await user.type(screen.getByLabelText('Labor hours'), '6');
    await user.click(screen.getByRole('button', { name: 'File claim' }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'File claim' }));
    await screen.findByText('filed');
    const keys = calls.filter((c) => c.method === 'POST').map((c) => c.headers.get('Idempotency-Key'));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });
});

describe('two people editing', () => {
  const theirs = { ...CLAIM, laborHours: 3, version: 2 };

  it('shows both versions and saves mine over theirs only when asked', async () => {
    answers.push((c) =>
      c.method === 'PUT'
        ? c.headers.get('If-Match') === '"1"'
          ? json(412, { code: 'STALE', detail: 'changed', current: theirs })
          : json(200, { ...CLAIM, laborHours: 5, version: 3 })
        : undefined,
    );
    const done = vi.fn();
    show(<EditClaim claim={CLAIM} onDone={done} />);
    const user = userEvent.setup();
    const hours = screen.getByLabelText('Labor hours');
    await user.clear(hours);
    await user.type(hours, '5');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    const title = await screen.findByRole('heading', { name: 'Someone else changed this claim' });
    expect(document.activeElement).toBe(title);
    expect(screen.getByRole('cell', { name: '5' })).toBeTruthy();
    expect(screen.getByRole('cell', { name: '3' })).toBeTruthy();
    expect(done).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('warranty.edit.P1')).toContain('"laborHours":"5"');

    await user.click(screen.getByRole('button', { name: 'Save my version' }));
    await waitFor(() => expect(done).toHaveBeenCalled());
    expect(calls.at(-1)?.headers.get('If-Match')).toBe('"2"');
    expect(sessionStorage.getItem('warranty.edit.P1')).toBeNull();
  });

  it('brings an unfinished edit back after a reload', () => {
    sessionStorage.setItem('warranty.edit.P1', JSON.stringify({ form: { ...fromClaim(CLAIM), laborHours: '7' }, version: 1 }));
    show(<EditClaim claim={theirs} onDone={() => {}} />);
    expect(screen.getByLabelText<HTMLInputElement>('Labor hours').value).toBe('7');
  });

  it('will not save mine over a decided claim', async () => {
    answers.push((c) => (c.method === 'PUT' ? json(412, { code: 'STALE', current: { ...theirs, status: 'APPROVED' } }) : undefined));
    show(<EditClaim claim={CLAIM} onDone={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect((await screen.findByRole<HTMLButtonElement>('button', { name: 'Save my version' })).disabled).toBe(true);
  });
});

describe('review', () => {
  const drafted = {
    claimNo: 'P1', claimVersion: 1, status: 'DRAFTED' as const, decision: 'DENY', clauseId: '5.9', clauseTitle: 'Rodents',
    quote: 'Not covered.', reason: 'Chewed.', withheldWhy: null, model: 'm', toolCalls: [],
  };

  it('shows the quote and its clause', () => {
    show(<DraftView draft={drafted} current={1} />);
    expect(screen.getByText('Not covered.').tagName).toBe('BLOCKQUOTE');
    expect(screen.getByText('Clause 5.9, Rodents')).toBeTruthy();
  });

  it('says so when a draft is withheld and shows nothing else', () => {
    show(<DraftView draft={{ ...drafted, status: 'WITHHELD', quote: 'made up' }} current={1} />);
    expect(screen.getByText('No supporting clause found')).toBeTruthy();
    expect(screen.queryByText('made up')).toBeNull();
  });

  it('marks a draft made for an older version', () => {
    show(<DraftView draft={drafted} current={2} />);
    expect(screen.getByText(/earlier version/)).toBeTruthy();
  });

  it('still allows a decision when the assistant is down', async () => {
    answers.push((c) => (c.url.includes('/decision') ? json(200, { ...CLAIM, status: 'DENIED' }) : undefined));
    show(<Review claim={CLAIM} open />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Draft a decision' }));
    await screen.findByText(/You can still decide/);
    await user.click(screen.getByRole('button', { name: 'Deny' }));
    await waitFor(() => expect(calls.at(-1)?.body).toEqual({ decision: 'DENIED', note: '' }));
  });
});
