import type { Claim } from '../api/types';
import { useFocus } from '../ui/parts';
import { differences, fromClaim, LABELS, type Form } from './form';

type Props = { mine: Form; theirs: Claim; busy: boolean; onMine: () => void; onTheirs: () => void };

/** Shows both versions side by side. Neither is thrown away until a button is pressed. */
export function Conflict({ mine, theirs, busy, onMine, onTheirs }: Props) {
  const heading = useFocus<HTMLHeadingElement>();
  const rows = differences(mine, fromClaim(theirs));
  return (
    <section className="conflict" aria-labelledby="conflict-title">
      <h2 id="conflict-title" ref={heading} tabIndex={-1}>
        Someone else changed this claim
      </h2>
      <p>Your version is still here. Choose which one to keep.</p>
      <table>
        <thead>
          <tr>
            <th scope="col">Field</th>
            <th scope="col">Yours</th>
            <th scope="col">Theirs</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key}>
              <th scope="row">{LABELS[r.key]}</th>
              <td>{r.mine || 'empty'}</td>
              <td>{r.theirs || 'empty'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {theirs.status !== 'SUBMITTED' && <p>Their version has been decided, so yours can no longer be saved.</p>}
      <div className="actions">
        <button className="primary" disabled={busy || theirs.status !== 'SUBMITTED'} onClick={onMine}>
          Save my version
        </button>
        <button disabled={busy} onClick={onTheirs}>
          Keep their version
        </button>
      </div>
    </section>
  );
}
