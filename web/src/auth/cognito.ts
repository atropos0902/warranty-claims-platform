/** Authorization code sign-in with PKCE. Used when the build knows a Cognito domain. */
import { signIn } from './session';

const domain = import.meta.env['VITE_COGNITO_DOMAIN'] as string | undefined;
const clientId = import.meta.env['VITE_COGNITO_CLIENT_ID'] as string | undefined;
export const cognitoEnabled = Boolean(domain && clientId);

const VERIFIER = 'warranty.verifier';
const STATE = 'warranty.state';
const redirect = () => `${location.origin}/signed-in`;

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export async function startSignIn(): Promise<void> {
  const verifier = base64Url(crypto.getRandomValues(new Uint8Array(32)));
  const state = base64Url(crypto.getRandomValues(new Uint8Array(16)));
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  sessionStorage.setItem(VERIFIER, verifier);
  sessionStorage.setItem(STATE, state);
  const query = new URLSearchParams({
    response_type: 'code',
    client_id: clientId ?? '',
    redirect_uri: redirect(),
    scope: 'openid',
    state,
    code_challenge: base64Url(new Uint8Array(digest)),
    code_challenge_method: 'S256',
  });
  location.assign(`${domain}/oauth2/authorize?${query}`);
}

export async function finishSignIn(search: string): Promise<void> {
  const query = new URLSearchParams(search);
  const code = query.get('code');
  if (!code || query.get('state') !== sessionStorage.getItem(STATE)) throw new Error('Sign-in did not finish. Start again.');
  const response = await fetch(`${domain}/oauth2/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: clientId ?? '',
      code,
      redirect_uri: redirect(),
      code_verifier: sessionStorage.getItem(VERIFIER) ?? '',
    }),
  });
  if (!response.ok) throw new Error('Sign-in did not finish. Start again.');
  sessionStorage.removeItem(VERIFIER);
  sessionStorage.removeItem(STATE);
  signIn(((await response.json()) as { access_token: string }).access_token);
}
