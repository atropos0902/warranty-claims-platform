import type { Claim, ClaimRequest } from '../api/types';

/** Form fields are text while they are being typed. */
export type FormLine = { partNo: string; qty: string; unitCost: string };
export type Form = {
  dealerCode: string;
  modelCode: string;
  serialNo: string;
  claimType: string;
  purchaseDate: string;
  failureDate: string;
  meterHours: string;
  repairCode: string;
  laborHours: string;
  travelMiles: string;
  notes: string;
  lines: FormLine[];
};

export const emptyForm = (): Form => ({
  dealerCode: '',
  modelCode: '',
  serialNo: '',
  claimType: 'S',
  purchaseDate: '',
  failureDate: '',
  meterHours: '',
  repairCode: '',
  laborHours: '',
  travelMiles: '',
  notes: '',
  lines: [{ partNo: '', qty: '1', unitCost: '' }],
});

const text = (n: number | null) => (n === null ? '' : String(n));
const optional = (s: string) => (s.trim() === '' ? null : Number(s));

export function fromClaim(c: Claim): Form {
  return {
    dealerCode: c.dealerCode,
    modelCode: c.modelCode,
    serialNo: c.serialNo,
    claimType: c.claimType,
    purchaseDate: c.purchaseDate,
    failureDate: c.failureDate,
    meterHours: text(c.meterHours),
    repairCode: c.repairCode,
    laborHours: String(c.laborHours),
    travelMiles: text(c.travelMiles),
    notes: c.notes ?? '',
    lines: c.lines.map((l) => ({ partNo: l.partNo, qty: String(l.qty), unitCost: String(l.unitCost) })),
  };
}

export function toRequest(f: Form): ClaimRequest {
  return {
    dealerCode: f.dealerCode,
    modelCode: f.modelCode,
    serialNo: f.serialNo.trim(),
    claimType: f.claimType,
    purchaseDate: f.purchaseDate,
    failureDate: f.failureDate,
    meterHours: optional(f.meterHours),
    repairCode: f.repairCode,
    laborHours: Number(f.laborHours),
    travelMiles: optional(f.travelMiles),
    notes: f.notes.trim() === '' ? null : f.notes,
    lines: f.lines
      .filter((l) => l.partNo.trim() !== '')
      .map((l) => ({ partNo: l.partNo.trim(), qty: Number(l.qty), unitCost: Number(l.unitCost) })),
  };
}

export const LABELS: Record<keyof Form, string> = {
  dealerCode: 'Dealer',
  modelCode: 'Model',
  serialNo: 'Serial number',
  claimType: 'Warranty',
  purchaseDate: 'Purchase date',
  failureDate: 'Failure date',
  meterHours: 'Meter hours',
  repairCode: 'Repair',
  laborHours: 'Labor hours',
  travelMiles: 'Travel miles',
  notes: 'Repair notes',
  lines: 'Parts',
};

const show = (f: Form, k: keyof Form) =>
  k === 'lines'
    ? f.lines.map((l) => `${l.qty} × ${l.partNo} at ${l.unitCost}`).join(', ')
    : (f[k] as string);

/** Fields where two versions of a claim disagree, in form order. */
export function differences(mine: Form, theirs: Form): { key: keyof Form; mine: string; theirs: string }[] {
  return (Object.keys(LABELS) as (keyof Form)[])
    .map((key) => ({ key, mine: show(mine, key), theirs: show(theirs, key) }))
    .filter((d) => d.mine !== d.theirs);
}
