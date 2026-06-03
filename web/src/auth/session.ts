/** Who is signed in. The token lives in sessionStorage, so it goes away with the tab. */
export type Person = { user: string; groups: string[] };

const KEY = 'warranty.token';
const listeners = new Set<() => void>();

export function currentToken(): string | null {
  return sessionStorage.getItem(KEY);
}

export function person(): Person | null {
  const token = currentToken();
  const payload = token?.split('.')[1];
  if (!payload) return null;
  try {
    const bytes = Uint8Array.from(atob(payload.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
    const claims = JSON.parse(new TextDecoder().decode(bytes)) as {
      sub: string;
      username?: string;
      exp?: number;
      'cognito:groups'?: string[];
    };
    if (claims.exp !== undefined && claims.exp * 1000 < Date.now()) return null;
    return { user: claims.username ?? claims.sub, groups: claims['cognito:groups'] ?? [] };
  } catch {
    return null;
  }
}

export function signIn(token: string): void {
  sessionStorage.setItem(KEY, token);
  // The load balancer reads this to send a dealer group to the system that owns it.
  const group = person()?.groups.find((g) => g.startsWith('GROUP_'));
  const secure = location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = group
    ? `dealer_group=${group.slice('GROUP_'.length)}; Path=/; SameSite=Lax${secure}`
    : `dealer_group=; Path=/; Max-Age=0; SameSite=Lax${secure}`;
  listeners.forEach((l) => l());
}

export function signOut(): void {
  sessionStorage.removeItem(KEY);
  document.cookie = 'dealer_group=; Path=/; Max-Age=0; SameSite=Lax';
  listeners.forEach((l) => l());
}

export function onChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export const is = (p: Person, role: string) => p.groups.includes(role);
