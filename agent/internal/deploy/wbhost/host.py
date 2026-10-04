"""Persistent owner for ONE existing WB meeting; never creates replacement rooms.

Session material stays in the private StateDirectory, never in arguments/logs.
The traffic publisher is independent: checking health does not restart the VPN.
"""
import asyncio
import json
import os
import re
import sys
import time
from pathlib import Path


def atomic_json(path, value):
    tmp = path.with_suffix('.tmp')
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(value, f, separators=(',', ':'))
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


class Recovery:
    """Consecutive failures + capped backoff; healthy probes do not rejoin."""
    def __init__(self):
        self.misses = 0
        self.attempt = 0

    def probe(self, connected):
        self.misses = 0 if connected else self.misses + 1
        if connected:
            self.attempt = 0
        return self.misses >= 3

    def retry_delay(self):
        value = min(300, 5 * 2 ** min(self.attempt, 6))
        self.attempt += 1
        return value


# Installed before WB creates peer connections. UI text alone can remain visible
# after a dead SFU socket. Inspect real peer states, without reading media/data.
PEERS = """(() => {
  const peers = new Set(), Original = window.RTCPeerConnection;
  window.__netguardConnected = () => [...peers].some(p =>
    p.connectionState === 'connected' || p.iceConnectionState === 'connected' || p.iceConnectionState === 'completed');
  window.RTCPeerConnection = class extends Original {
    constructor(...args) { super(...args); peers.add(this);
      this.addEventListener('connectionstatechange', () => { if (this.connectionState === 'closed') peers.delete(this); });
    }
  };
})()"""

REFRESH = """async () => {
  if (location.origin !== 'https://stream.wb.ru') return 0;
  const device = localStorage.getItem('wb_auth_api_device_id');
  if (!device) return 401;
  try {
    const response = await fetch('https://auth-stream.wb.ru/v2/auth/slide-v3', {
      method:'POST', credentials:'include', signal:AbortSignal.timeout(20000),
      headers:{'wb-apptype':'web', deviceId:device, 'X-Request-ID':crypto.randomUUID()}
    });
    if (!response.ok) return response.status;
    const result = await response.json();
    if (!result.payload?.access_token) return 401;
    const slice = JSON.parse(localStorage.getItem('wb_auth_auth_slice') || '{}');
    slice.accessToken = result.payload.access_token;
    localStorage.setItem('wb_auth_auth_slice', JSON.stringify(slice));
    return 200;
  } catch { return 0; }
}"""


class Host:
    def __init__(self, room, directory):
        if not re.fullmatch(r'https://stream\.wb\.ru/room/[A-Za-z0-9_-]{1,128}', room):
            raise ValueError('invalid room')
        self.room, self.directory = room, Path(directory)
        self.state = self.directory / 'state.json'
        self.recovery = Recovery()
        self.auth_failures = 0
        self.auth_needed = False
        self.last_refresh = 0
        self.last_status = None
        self.blocked = False
        self.phase = 'starting'

    def report(self, status):
        atomic_json(self.directory / 'status.json', {'state': status, 'phase': self.phase, 'updated': int(time.time())})
        if status != self.last_status:
            print('WB host:', status, flush=True)
            self.last_status = status

    async def refresh(self, page, context):
        status = await page.evaluate(REFRESH)
        if status == 200:
            self.auth_failures = 0
            self.auth_needed = False
            # Save rotated refresh cookies immediately, including after restarts.
            await self.save(context)
        elif status in (401, 403):
            self.auth_failures += 1
            self.auth_needed = self.auth_failures >= 3
        self.last_refresh = time.monotonic()
        return status

    async def save(self, context):
        state = await context.storage_state()
        # Do not overwrite the only usable refresh token with an empty logged-out context.
        if any(c['name'] == 'wbx-refresh' and c['value'] for c in state['cookies']):
            state['origins'] = [o for o in state['origins'] if o['origin'] == 'https://stream.wb.ru']
            state['cookies'] = [c for c in state['cookies'] if c['domain'].lstrip('.') in ('wb.ru', 'stream.wb.ru', 'auth-stream.wb.ru')]
            atomic_json(self.state, state)

    async def join(self, page, context):
        self.phase = 'open_room'
        self.report('reconnecting')
        # Same URL for the lifetime of the profile. Never create or delete meetings.
        response = await page.goto(self.room, wait_until='domcontentloaded', timeout=45000)
        self.blocked = False
        if response and response.status == 498:
            await asyncio.sleep(15)
            body = (await page.locator('body').inner_text(timeout=5000)).lower()
            self.blocked = 'подозрительная активность' in body or 'suspicious activity' in body
            if self.blocked:
                self.report('wb_blocked')
                return False
        refreshed = False
        for _ in range(5):
            self.phase = 'refresh_login'
            if await self.refresh(page, context) == 200:
                refreshed = True
                break
            await asyncio.sleep(3)
        if self.auth_needed:
            self.report('needs_login')
            return False
        if not refreshed:
            self.report('reconnecting')
            return False
        # Refresh localStorage is picked up by the application on navigation.
        self.phase = 'load_room'
        await page.goto(self.room, wait_until='domcontentloaded', timeout=45000)
        self.phase = 'join_room'
        # WB may restore the call directly after navigation. Clicking a missing
        # pre-join button in that state used to trigger endless rejoin attempts.
        if not await page.evaluate('window.__netguardConnected?.() === true'):
            join = page.locator('[data-test="join-button"]')
            try:
                await join.click(timeout=30000)
            except Exception:
                if not await page.evaluate('window.__netguardConnected?.() === true'):
                    raise
        self.phase = 'connect_media'
        await page.wait_for_function('window.__netguardConnected?.() === true', timeout=45000)
        await self.save(context)
        return True

    async def connected(self, page):
        return page.url.split('?')[0].split('#')[0].rstrip('/') == self.room and await page.evaluate('window.__netguardConnected?.() === true')

    async def run(self):
        from playwright.async_api import async_playwright
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(channel='chromium', headless=True, args=[
                '--disable-dev-shm-usage', '--disable-gpu', '--js-flags=--max-old-space-size=192',
                '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'])
            context = await browser.new_context(storage_state=str(self.state), permissions=['microphone', 'camera'])
            await context.add_init_script(PEERS)
            page = await context.new_page()
            joined = False
            while True:
                try:
                    if not joined:
                        joined = await asyncio.wait_for(self.join(page, context), 150)
                        if not joined:
                            await asyncio.sleep(300 if self.auth_needed or self.blocked else self.recovery.retry_delay())
                            continue
                    ok = await asyncio.wait_for(self.connected(page), 15)
                    if self.recovery.probe(ok):
                        joined = False
                        self.report('reconnecting')
                        await asyncio.sleep(self.recovery.retry_delay())
                        continue
                    if time.monotonic() - self.last_refresh >= (300 if self.auth_needed else 60):
                        await asyncio.wait_for(self.refresh(page, context), 30)
                    self.report('needs_login' if self.auth_needed else 'hosting' if ok else 'checking')
                    await asyncio.wait_for(self.save(context), 15)
                    await asyncio.sleep(30)
                except Exception as error:
                    # Exceptions from a browser can contain URLs/session values. Never log them.
                    self.report('needs_login' if self.auth_needed else 'reconnecting')
                    print('WB host retry:', self.phase, type(error).__name__, flush=True)
                    if not browser.is_connected() or page.is_closed():
                        return 1  # systemd restarts a crashed browser, retaining rotated state.
                    joined = False
                    await asyncio.sleep(300 if self.auth_needed else self.recovery.retry_delay())


if __name__ == '__main__':
    os.umask(0o077)
    try:
        sys.exit(asyncio.run(Host(sys.argv[1], sys.argv[2]).run()))
    except Exception:
        print('WB host: startup_failed', flush=True)
        sys.exit(1)
