import createClient, { type Middleware } from 'openapi-fetch';
import type { paths as AssistantPaths } from './assistant';
import type { paths as ClaimsPaths } from './claims';
import type { paths as PricingPaths } from './pricing';
import { currentToken, signOut } from '../auth/session';
import type { Claim, ClaimRequest, ClaimSummary, Dealer, Draft, Group, Reference } from './types';

/** Any answer that is not a success. `current` comes with a stale edit, `fields` with a bad form. */
export class Problem extends Error {
  readonly status: number;
  readonly code: string;
  readonly fields: Record<string, string>;
  readonly current: Claim | null;
  readonly claimNo: string | null;

  constructor(status: number, body: Record<string, unknown>) {
    super(typeof body['detail'] === 'string' ? body['detail'] : 'The server could not answer. Try again.');
    this.status = status;
    this.code = typeof body['code'] === 'string' ? body['code'] : 'UNKNOWN';
    this.fields = (body['fields'] as Record<string, string> | undefined) ?? {};
    this.current = (body['current'] as Claim | undefined) ?? null;
    this.claimNo = (body['claimNo'] as string | undefined) ?? null;
  }
}

const auth: Middleware = {
  onRequest({ request }) {
    const token = currentToken();
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    return request;
  },
  async onResponse({ response }) {
    if (response.ok) return;
    if (response.status === 401) signOut();
    const body = (await response.json().catch(() => ({}))) as Record<string, unknown>;
    throw new Problem(response.status, body);
  },
};

const options = { baseUrl: location.origin, fetch: (r: Request) => globalThis.fetch(r) };
const claims = createClient<ClaimsPaths>(options);
const pricing = createClient<PricingPaths>(options);
const assistant = createClient<AssistantPaths>(options);
claims.use(auth);
pricing.use(auth);
assistant.use(auth);

const tag = (version: number) => `"${version}"`;

export const api = {
  async list(status: string | undefined): Promise<ClaimSummary[]> {
    const r = await claims.GET('/api/v1/claims', { params: { query: status ? { status } : {} } });
    return r.data as ClaimSummary[];
  },
  async claim(claimNo: string): Promise<Claim> {
    const r = await claims.GET('/api/v1/claims/{claimNo}', { params: { path: { claimNo } } });
    return r.data as Claim;
  },
  async file(key: string, body: ClaimRequest): Promise<Claim> {
    const r = await claims.POST('/api/v1/claims', {
      params: { header: { 'Idempotency-Key': key } },
      body: body as never,
    });
    return r.data as Claim;
  },
  async edit(claimNo: string, version: number, body: ClaimRequest): Promise<Claim> {
    const r = await claims.PUT('/api/v1/claims/{claimNo}', {
      params: { path: { claimNo }, header: { 'If-Match': tag(version) } },
      body: body as never,
    });
    return r.data as Claim;
  },
  async decide(claimNo: string, version: number, decision: 'APPROVED' | 'DENIED', note: string): Promise<Claim> {
    const r = await claims.POST('/api/v1/claims/{claimNo}/decision', {
      params: { path: { claimNo }, header: { 'If-Match': tag(version) } },
      body: { decision, note },
    });
    return r.data as Claim;
  },
  async groups(): Promise<Group[]> {
    const r = await claims.GET('/api/v1/dealer-groups');
    return r.data as Group[];
  },
  async dealers(): Promise<Dealer[]> {
    const r = await claims.GET('/api/v1/dealer-groups/dealers');
    return r.data as Dealer[];
  },
  async reference(): Promise<Reference> {
    const r = await pricing.GET('/api/v1/pricing/reference');
    return r.data as Reference;
  },
  async draft(claimNo: string): Promise<Draft> {
    const r = await assistant.POST('/api/v1/assistant/claims/{claimNo}/draft', { params: { path: { claimNo } } });
    return r.data as Draft;
  },
};
