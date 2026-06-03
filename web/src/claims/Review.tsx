import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, Problem } from '../api/client';
import type { Claim, Draft } from '../api/types';
import { Alert } from '../ui/parts';

/** What the adjuster works with: the drafted decision, the clause it rests on, and the two buttons. */
export function Review({ claim, open }: { claim: Claim; open: boolean }) {
  const [note, setNote] = useState('');
  const cache = useQueryClient();
  const draft = useMutation({ mutationFn: () => api.draft(claim.claimNo) });
  const decide = useMutation({
    mutationFn: (d: 'APPROVED' | 'DENIED') => api.decide(claim.claimNo, claim.version, d, note),
    onSuccess: (c) => cache.setQueryData(['claim', claim.claimNo], c),
    onError: (e) => {
      if (e instanceof Problem && e.current) cache.setQueryData(['claim', claim.claimNo], e.current);
    },
  });

  return (
    <section className="review" aria-labelledby="review">
      <h2 id="review">Decision</h2>
      <button onClick={() => draft.mutate()} disabled={draft.isPending}>
        {draft.isPending ? 'Reading the policy' : 'Draft a decision'}
      </button>
      <div aria-live="polite">
        {draft.error && <Alert>The assistant is not answering. You can still decide.</Alert>}
        {draft.data && <DraftView draft={draft.data} current={claim.version} />}
      </div>
      {open ? (
        <>
          <label htmlFor="note">Note to the dealer</label>
          <textarea id="note" rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
          {decide.error && (
            <Alert>
              {decide.error instanceof Problem && decide.error.code === 'STALE'
                ? 'The claim changed while you were reading it. The new version is on screen, check it before you decide.'
                : decide.error.message}
            </Alert>
          )}
          <div className="actions">
            <button className="primary" disabled={decide.isPending || !claim.price} onClick={() => decide.mutate('APPROVED')}>
              Approve
            </button>
            <button className="deny" disabled={decide.isPending} onClick={() => decide.mutate('DENIED')}>
              Deny
            </button>
          </div>
        </>
      ) : (
        claim.decisionNote && <p>{claim.decisionNote}</p>
      )}
    </section>
  );
}

export function DraftView({ draft, current }: { draft: Draft; current: number }) {
  if (draft.status === 'WITHHELD') return <p className="note">No supporting clause found</p>;
  return (
    <figure className="draft">
      {draft.claimVersion !== current && <p className="note">This draft was made for an earlier version of the claim.</p>}
      <p>
        Suggests <strong>{draft.decision === 'APPROVE' ? 'approve' : 'deny'}</strong>. {draft.reason}
      </p>
      <blockquote>{draft.quote}</blockquote>
      <figcaption>
        Clause {draft.clauseId}, {draft.clauseTitle}
      </figcaption>
    </figure>
  );
}
