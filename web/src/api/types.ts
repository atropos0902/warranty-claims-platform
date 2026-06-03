import type { components as C } from './claims';
import type { components as A } from './assistant';
import type { components as P } from './pricing';

/** The contract marks every field optional. These say which ones really can be missing. */
type Price = Required<Omit<C['schemas']['Price'], 'steps'>> & { steps: Required<C['schemas']['Step']>[] };

export type Claim = Required<
  Omit<C['schemas']['ClaimView'], 'price' | 'meterHours' | 'travelMiles' | 'notes' | 'decisionNote' | 'lines'>
> & {
  lines: Line[];
  price: Price | null;
  meterHours: number | null;
  travelMiles: number | null;
  notes: string | null;
  decisionNote: string | null;
};
export type Line = Required<C['schemas']['Line']>;
export type ClaimSummary = Required<Omit<C['schemas']['ClaimSummary'], 'totalPay'>> & { totalPay: number | null };
export type ClaimRequest = Omit<C['schemas']['ClaimRequest'], 'meterHours' | 'travelMiles' | 'notes'> & {
  meterHours: number | null;
  travelMiles: number | null;
  notes: string | null;
};
export type Group = Required<C['schemas']['Group']>;
export type Dealer = Required<C['schemas']['Dealer']>;
export type Reference = { models: string[]; repairCodes: Required<P['schemas']['RepairCode']>[] };
export type Draft = {
  [K in keyof Required<A['schemas']['Draft']>]: Required<A['schemas']['Draft']>[K] | null;
} & { status: 'DRAFTED' | 'WITHHELD'; claimVersion: number };
