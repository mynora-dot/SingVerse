/** Only public approval information belongs in a QR link; never a TV poll secret or token. */
export function readApprovalLink(hash: string): { id: string; code: string } | null {
  if (hash.split('?')[0] !== '#/system/tv-devices') return null
  const query = new URLSearchParams(hash.slice(hash.indexOf('?') + 1))
  const id = query.get('authorization') ?? '', code = query.get('code') ?? ''
  if (!/^[a-zA-Z0-9-]{1,80}$/.test(id) || !/^\d{6}$/.test(code)) return null
  return { id, code }
}
