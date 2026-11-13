/**
 * Idempotency keys for one logical user action.
 *
 * The rule that makes retries safe: a retry of the SAME request must reuse the SAME key (so the server
 * replays the first outcome instead of booking twice), while a CHANGED request must get a NEW key (else the
 * server rejects it as IDEM_KEY_REUSED). So the key is bound to a fingerprint of the request body.
 */
export class IdempotentAction {
  private key: string | null = null;
  private fingerprint: string | null = null;

  keyFor(body: unknown): string {
    const fp = stableStringify(body);
    if (this.key === null || fp !== this.fingerprint) {
      this.key = newKey();
      this.fingerprint = fp;
    }
    return this.key;
  }

  /** Call after a definitive outcome (success, or a 4xx business error) so the next attempt is new. */
  reset(): void {
    this.key = null;
    this.fingerprint = null;
  }
}

export function newKey(): string {
  return 'web-' + crypto.randomUUID();
}

/** JSON with sorted object keys, so {a,b} and {b,a} fingerprint the same. */
export function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return '[' + value.map(stableStringify).join(',') + ']';
  }
  const obj = value as Record<string, unknown>;
  return '{' + Object.keys(obj).sort()
    .filter(k => obj[k] !== undefined)
    .map(k => JSON.stringify(k) + ':' + stableStringify(obj[k])).join(',') + '}';
}
