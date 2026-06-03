import { useSyncExternalStore, useEffect, useState } from 'react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router';
import { finishSignIn } from './auth/cognito';
import { currentToken, is, onChange, person, signOut } from './auth/session';
import { SignIn } from './auth/SignIn';
import { ClaimList } from './claims/ClaimList';
import { ClaimPage } from './claims/ClaimPage';
import { NewClaim } from './claims/NewClaim';
import { Alert } from './ui/parts';

export function App() {
  useSyncExternalStore(onChange, currentToken);
  const who = person();
  const { pathname } = useLocation();
  if (pathname === '/signed-in') return <SignedIn />;
  if (!who) return <SignIn />;
  return (
    <>
      <a className="skip" href="#main">
        Skip to content
      </a>
      <header>
        <Link to="/claims">Warranty claims</Link>
        <span>{who.user}</span>
        <button onClick={signOut}>Sign out</button>
      </header>
      <main id="main">
        <Routes>
          <Route path="/claims" element={<ClaimList person={who} />} />
          {is(who, 'DEALER') && <Route path="/claims/new" element={<NewClaim />} />}
          <Route path="/claims/:claimNo" element={<ClaimPage key={pathname} person={who} />} />
          <Route path="*" element={<Navigate to="/claims" replace />} />
        </Routes>
      </main>
    </>
  );
}

function SignedIn() {
  const [problem, setProblem] = useState<string | null>(null);
  const { search } = useLocation();
  useEffect(() => {
    finishSignIn(search).then(
      // A full load, so the load balancer sees the dealer group cookie.
      () => location.replace('/claims'),
      (e: Error) => setProblem(e.message),
    );
  }, [search]);
  return <main className="sign-in">{problem ? <Alert>{problem}</Alert> : <p role="status">Signing in</p>}</main>;
}
