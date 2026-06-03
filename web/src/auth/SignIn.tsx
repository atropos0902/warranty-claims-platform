import { useState } from 'react';
import { cognitoEnabled, startSignIn } from './cognito';
import { signIn, type Person } from './session';

const PEOPLE: (Person & { label: string })[] = [
  { user: 'prairie', groups: ['DEALER', 'GROUP_NORTH'], label: 'Dealer, northern group' },
  { user: 'piedmont', groups: ['DEALER', 'GROUP_SOUTH'], label: 'Dealer, southern group' },
  { user: 'adjuster', groups: ['ADJUSTER'], label: 'Adjuster' },
];

export function SignIn() {
  const [problem, setProblem] = useState<string | null>(null);

  async function local(p: Person) {
    setProblem(null);
    try {
      const r = await fetch('/dev/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ user: p.user, groups: p.groups }),
      });
      if (!r.ok) throw new Error();
      signIn(((await r.json()) as { token: string }).token);
    } catch {
      setProblem('The claims service is not answering.');
    }
  }

  return (
    <main className="sign-in">
      <h1>Warranty claims</h1>
      {cognitoEnabled ? (
        <button className="primary" onClick={() => void startSignIn()}>
          Sign in
        </button>
      ) : (
        <>
          <p>Choose who to be. This page exists only in local runs.</p>
          {PEOPLE.map((p) => (
            <button key={p.user} onClick={() => void local(p)}>
              {p.label}
            </button>
          ))}
        </>
      )}
      {problem && <p role="alert">{problem}</p>}
    </main>
  );
}
