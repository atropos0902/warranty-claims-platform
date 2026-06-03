import { useEffect, useRef, type ReactNode } from 'react';

/** Moves focus here when the element appears, so a keyboard or a screen reader lands on what changed. */
export function useFocus<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useEffect(() => ref.current?.focus(), []);
  return ref;
}

export function Heading({ children }: { children: ReactNode }) {
  const ref = useFocus<HTMLHeadingElement>();
  return (
    <h1 ref={ref} tabIndex={-1}>
      {children}
    </h1>
  );
}

export const Alert = ({ children }: { children: ReactNode }) => (
  <p role="alert" className="alert">
    {children}
  </p>
);

const WORDS: Record<string, string> = { SUBMITTED: 'Waiting', APPROVED: 'Approved', DENIED: 'Denied' };

export const Status = ({ value }: { value: string }) => (
  <span className={`status ${value.toLowerCase()}`}>{WORDS[value] ?? value}</span>
);

export const money = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
