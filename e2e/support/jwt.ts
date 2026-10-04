import { createHmac } from 'node:crypto';

const b64 = (v: object | Buffer) => Buffer.from(v instanceof Buffer ? v : JSON.stringify(v)).toString('base64url');

/** Builds an HS256 JWT. Used to forge/expire tokens and prove the server rejects them. */
export function signJwt(claims: Record<string, unknown>, secret: string, header: Record<string, unknown> = { alg: 'HS256', typ: 'JWT' }) {
  const data = `${b64(header)}.${b64(claims)}`;
  return `${data}.${createHmac('sha256', secret).update(data).digest('base64url')}`;
}

export const unsigned = (claims: Record<string, unknown>) => `${b64({ alg: 'none', typ: 'JWT' })}.${b64(claims)}.`;

/** The dev secret from docker-compose (DEV/CI only). Override with E2E_JWT_SECRET when the stack uses another. */
export const DEV_SECRET = process.env.E2E_JWT_SECRET ?? 'dev-only-secret-dev-only-secret-0123456789';
