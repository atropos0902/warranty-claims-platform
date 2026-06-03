import { useQuery } from '@tanstack/react-query';
import { useId, type ReactNode } from 'react';
import { api } from '../api/client';
import { LABELS, type Form, type FormLine } from './form';

type Props = {
  form: Form;
  onChange: (f: Form) => void;
  fields: Record<string, string>;
  children: ReactNode;
  onSubmit: () => void;
};

export function ClaimForm({ form, onChange, fields, children, onSubmit }: Props) {
  const reference = useQuery({ queryKey: ['reference'], queryFn: api.reference, staleTime: Infinity });
  const dealers = useQuery({ queryKey: ['dealers'], queryFn: api.dealers, staleTime: Infinity });
  const set = (k: keyof Form) => (value: string) => onChange({ ...form, [k]: value });
  const setLine = (i: number, l: Partial<FormLine>) =>
    onChange({ ...form, lines: form.lines.map((old, n) => (n === i ? { ...old, ...l } : old)) });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit();
      }}
    >
      <div className="grid">
        <Field name="dealerCode" error={fields['dealerCode']}>
          {(p) => (
            <select {...p} required value={form.dealerCode} onChange={(e) => set('dealerCode')(e.target.value)}>
              <option value="">Choose a dealer</option>
              {dealers.data?.map((d) => (
                <option key={d.dealerCode} value={d.dealerCode}>
                  {d.dealerName}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field name="modelCode" error={fields['modelCode']}>
          {(p) => (
            <select {...p} required value={form.modelCode} onChange={(e) => set('modelCode')(e.target.value)}>
              <option value="">Choose a model</option>
              {reference.data?.models.map((m) => <option key={m}>{m}</option>)}
            </select>
          )}
        </Field>
        <Field name="serialNo" error={fields['serialNo']}>
          {(p) => <input {...p} required maxLength={30} value={form.serialNo} onChange={(e) => set('serialNo')(e.target.value)} />}
        </Field>
        <Field name="claimType" error={fields['claimType']}>
          {(p) => (
            <select {...p} value={form.claimType} onChange={(e) => set('claimType')(e.target.value)}>
              <option value="S">Standard</option>
              <option value="E">Extended</option>
              <option value="G">Goodwill</option>
            </select>
          )}
        </Field>
        <Field name="purchaseDate" error={fields['purchaseDate']}>
          {(p) => <input {...p} type="date" required value={form.purchaseDate} onChange={(e) => set('purchaseDate')(e.target.value)} />}
        </Field>
        <Field name="failureDate" error={fields['failureDate']}>
          {(p) => <input {...p} type="date" required value={form.failureDate} onChange={(e) => set('failureDate')(e.target.value)} />}
        </Field>
        <Field name="meterHours" error={fields['meterHours']}>
          {(p) => <input {...p} type="number" min="0" step="0.1" value={form.meterHours} onChange={(e) => set('meterHours')(e.target.value)} />}
        </Field>
        <Field name="repairCode" error={fields['repairCode']}>
          {(p) => (
            <select {...p} required value={form.repairCode} onChange={(e) => set('repairCode')(e.target.value)}>
              <option value="">Choose a repair</option>
              {reference.data?.repairCodes.map((r) => (
                <option key={r.repairCode} value={r.repairCode}>
                  {r.description}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field name="laborHours" error={fields['laborHours']}>
          {(p) => <input {...p} type="number" required min="0" step="0.01" value={form.laborHours} onChange={(e) => set('laborHours')(e.target.value)} />}
        </Field>
        <Field name="travelMiles" error={fields['travelMiles']}>
          {(p) => <input {...p} type="number" min="0" step="0.1" value={form.travelMiles} onChange={(e) => set('travelMiles')(e.target.value)} />}
        </Field>
      </div>
      <fieldset>
        <legend>Parts</legend>
        {form.lines.map((l, i) => (
          <div className="line" key={i}>
            <input aria-label={`Part number, line ${i + 1}`} placeholder="Part number" maxLength={20} value={l.partNo} onChange={(e) => setLine(i, { partNo: e.target.value })} />
            <input aria-label={`Quantity, line ${i + 1}`} type="number" min="1" step="1" required={l.partNo !== ''} value={l.qty} onChange={(e) => setLine(i, { qty: e.target.value })} />
            <input aria-label={`Unit cost, line ${i + 1}`} placeholder="Unit cost" type="number" min="0" step="0.0001" required={l.partNo !== ''} value={l.unitCost} onChange={(e) => setLine(i, { unitCost: e.target.value })} />
            <button type="button" aria-label={`Remove line ${i + 1}`} onClick={() => onChange({ ...form, lines: form.lines.filter((_, n) => n !== i) })}>
              Remove
            </button>
          </div>
        ))}
        <button type="button" onClick={() => onChange({ ...form, lines: [...form.lines, { partNo: '', qty: '1', unitCost: '' }] })}>
          Add a part
        </button>
      </fieldset>
      <Field name="notes" error={fields['notes']}>
        {(p) => <textarea {...p} rows={5} maxLength={2000} value={form.notes} onChange={(e) => set('notes')(e.target.value)} />}
      </Field>
      {children}
    </form>
  );
}

type Control = { id: string; 'aria-invalid'?: true; 'aria-describedby'?: string };

function Field({ name, error, children }: { name: keyof Form; error: string | undefined; children: (p: Control) => ReactNode }) {
  const id = useId();
  const control: Control = error ? { id, 'aria-invalid': true, 'aria-describedby': `${id}-e` } : { id };
  return (
    <div className="field">
      <label htmlFor={id}>{LABELS[name]}</label>
      {children(control)}
      {error && (
        <span className="error" id={`${id}-e`}>
          {error}
        </span>
      )}
    </div>
  );
}
