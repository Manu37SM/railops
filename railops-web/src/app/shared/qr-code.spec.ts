import { qrPath } from './qr-code';

describe('qrPath', () => {
  it('draws a square code with a 4-module quiet zone, the same every time', () => {
    const a = qrPath('otpauth://totp/RailOps:a%40b.c?secret=JBSWY3DPEHPK3PXP&issuer=RailOps')!;
    const b = qrPath('otpauth://totp/RailOps:a%40b.c?secret=JBSWY3DPEHPK3PXP&issuer=RailOps')!;
    expect(a.path).toBe(b.path);
    expect(a.side).toBe(a.modules + 8);
    expect(a.viewBox).toBe(`0 0 ${a.side} ${a.side}`);
    expect(a.modules % 4).toBe(1);   // QR sizes are 21, 25, 29, ... modules
    // The top-left finder pattern starts right after the quiet zone.
    expect(a.path.startsWith('M4 4h1v1h-1z')).toBe(true);
  });

  it('draws nothing for empty data', () => {
    expect(qrPath('')).toBeNull();
  });
});
