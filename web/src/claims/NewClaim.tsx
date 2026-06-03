import { useMutation } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, Problem } from '../api/client';
import { Heading, Alert } from '../ui/parts';
import { ClaimForm } from './ClaimForm';
import { emptyForm, toRequest, type Form } from './form';

export function NewClaim() {
  const [form, setForm] = useState<Form>(emptyForm);
  // One key for this claim. Pressing the button again after a lost answer must not file it twice.
  const key = useRef(crypto.randomUUID());
  const go = useNavigate();
  const file = useMutation({
    mutationFn: () => api.file(key.current, toRequest(form)),
    onSuccess: (c) => go(`/claims/${c.claimNo}`),
  });
  const problem = file.error instanceof Problem ? file.error : null;

  return (
    <>
      <Heading>File a claim</Heading>
      {file.error && (
        <Alert>
          {problem?.code === 'KEY_REUSED' && problem.claimNo ? (
            <>
              The first version of this claim was filed. <Link to={`/claims/${problem.claimNo}`}>Open it</Link> to make changes.
            </>
          ) : (
            file.error.message
          )}
        </Alert>
      )}
      <ClaimForm form={form} onChange={setForm} fields={problem?.fields ?? {}} onSubmit={() => file.mutate()}>
        <button className="primary" disabled={file.isPending}>
          {file.isPending ? 'Filing' : 'File claim'}
        </button>
      </ClaimForm>
    </>
  );
}
