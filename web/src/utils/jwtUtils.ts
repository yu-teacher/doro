import { UserRole } from '../types/auth';

export interface DecodedJwt {
  sub: string; // userId
  email: string;
  sid: string; // sessionId
  uidx: number;
  role: UserRole;
  exp: number;
  iat: number;
}

export function parseJwtPayload(token: string): DecodedJwt | null {
  try {
    const parts = token.split('.');
    if (parts.length < 2) return null;
    const base64Url = parts[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    return JSON.parse(jsonPayload) as DecodedJwt;
  } catch {
    return null;
  }
}
