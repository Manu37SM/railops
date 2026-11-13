import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import qrcode from 'qrcode-generator';

/**
 * A QR code drawn in the page as one SVG path, from data that never leaves the browser (no image service: the
 * authenticator secret must not be sent anywhere). Bound with [attr.d], so nothing goes through innerHTML and the
 * Content-Security-Policy needs no change.
 */
@Component({
  selector: 'ro-qr-code',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (qr(); as q) {
      <svg [attr.viewBox]="q.viewBox" [attr.width]="size()" [attr.height]="size()" role="img" [attr.aria-label]="label()"
           shape-rendering="crispEdges" class="qr">
        <rect [attr.width]="q.side" [attr.height]="q.side" fill="#fff" />
        <path [attr.d]="q.path" fill="#000" />
      </svg>
    }
  `,
  styles: `.qr { display: block; background: #fff; border-radius: 4px; }`,
})
export class QrCode {
  readonly data = input.required<string>();
  readonly label = input('QR code');
  readonly size = input(200);

  protected readonly qr = computed(() => qrPath(this.data()));
}

/** QR modules as one SVG path (one 1×1 square per dark module), with a 4-module quiet zone. Null for empty data. */
export function qrPath(data: string): { path: string; side: number; viewBox: string; modules: number } | null {
  if (!data) return null;
  const q = qrcode(0, 'M');
  q.addData(data);
  q.make();
  const n = q.getModuleCount();
  const quiet = 4;
  let path = '';
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (q.isDark(r, c)) path += `M${c + quiet} ${r + quiet}h1v1h-1z`;
    }
  }
  const side = n + quiet * 2;
  return { path, side, viewBox: `0 0 ${side} ${side}`, modules: n };
}
