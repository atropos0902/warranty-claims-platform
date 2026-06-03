import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, Problem } from '../api/client';
import type { Claim } from '../api/types';
import { Alert } from '../ui/parts';
import { ClaimForm } from './ClaimForm';
import { Conflict } from './Conflict';
import { fromClaim, toRequest, type Form } from './form';

const draftKey = (claimNo: string) => `warranty.edit.${claimNo}`;

/** An unfinished edit survives a reload, conflict included. */
function saved(claimNo: string): { form: Form; version: number } | null {
  try {
    const text = sessionStorage.getItem(draftKey(claimNo));
    return text ? (JSON.parse(text) as { form: Form; version: number }) : null;
  } catch {
    return null;
  }
}

export function EditClaim({ claim, onDone }: { claim: Claim; onDone: () => void }) {
  const [{ form, version }, setState] = useState(() => saved(claim.claimNo) ?? { form: fromClaim(claim), version: claim.version });
  const [theirs, setTheirs] = useState<Claim | null>(null);
  const cache = useQueryClient();

  const change = (next: Form) => {
    sessionStorage.setItem(draftKey(claim.claimNo), JSON.stringify({ form: next, version }));
    setState({ form: next, version });
  };
  const finish = (c: Claim) => {
    sessionStorage.removeItem(draftKey(claim.claimNo));
    cache.setQueryData(['claim', claim.claimNo], c);
    onDone();
  };
  const save = useMutation({
    mutationFn: (base: number) => api.edit(claim.claimNo, base, toRequest(form)),
    onSuccess: finish,
    onError: (e) => {
      if (e instanceof Problem && e.code === 'STALE' && e.current) setTheirs(e.current);
    },
  });
  const problem = save.error instanceof Problem ? save.error : null;

  if (theirs) {
    return (
      <Conflict
        mine={form}
        theirs={theirs}
        busy={save.isPending}
        onMine={() => save.mutate(theirs.version)}
        onTheirs={() => finish(theirs)}
      />
    );
  }
  return (
    <>
      {save.error && <Alert>{save.error.message}</Alert>}
      <ClaimForm form={form} onChange={change} fields={problem?.fields ?? {}} onSubmit={() => save.mutate(version)}>
        <div className="actions">
          <button className="primary" disabled={save.isPending}>
            Save
          </button>
          <button type="button" onClick={() => finish(claim)}>
            Discard changes
          </button>
        </div>
      </ClaimForm>
    </>
  );
}
