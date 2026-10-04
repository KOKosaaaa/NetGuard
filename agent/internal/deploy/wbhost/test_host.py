import asyncio
import importlib.util
import json
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
from pathlib import Path

spec = importlib.util.spec_from_file_location('host', Path(__file__).with_name('host.py'))
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)


class RecoveryTests(unittest.TestCase):
    def test_restored_call_does_not_wait_for_missing_join_button(self):
        with tempfile.TemporaryDirectory() as d:
            h = host.Host('https://stream.wb.ru/room/existing', d)
            class Page:
                async def goto(self, url, **kwargs): return type('Response', (), {'status':200})()
                async def evaluate(self, script): return 200 if script == host.REFRESH else True
                def locator(self, selector): raise AssertionError('already connected, no join click needed')
                async def wait_for_function(self, script, **kwargs): pass
            class Context:
                async def storage_state(self): return {'cookies':[], 'origins':[]}
            self.assertTrue(asyncio.run(h.join(Page(), Context())))

    def test_network_failure_during_refresh_never_attempts_guest_join(self):
        with tempfile.TemporaryDirectory() as d:
            h = host.Host('https://stream.wb.ru/room/existing', d)
            class Page:
                async def goto(self, url, **kwargs): return type('Response', (), {'status':200})()
                async def evaluate(self, script): return 0
                def locator(self, selector): raise AssertionError('must retry refresh before joining')
            with patch.object(host.asyncio,'sleep',new=AsyncMock()):
                self.assertFalse(asyncio.run(h.join(Page(),None)))
            self.assertFalse(h.auth_needed)

    def test_wb_ip_block_never_reports_ready_or_attempts_join(self):
        with tempfile.TemporaryDirectory() as d:
            h = host.Host('https://stream.wb.ru/room/existing', d)
            class Body:
                async def inner_text(self, **kwargs): return 'Подозрительная активность'
            class Page:
                async def goto(self, url, **kwargs):
                    self.url = url
                    return type('Response', (), {'status':498})()
                def locator(self, selector):
                    if selector != 'body': raise AssertionError('must not try to join blocked page')
                    return Body()
            with patch.object(host.asyncio, 'sleep', new=AsyncMock()):
                self.assertFalse(asyncio.run(h.join(Page(), None)))
            self.assertEqual(json.loads((Path(d)/'status.json').read_text())['state'], 'wb_blocked')

    def test_recovery_reuses_original_room_without_creation(self):
        with tempfile.TemporaryDirectory() as d:
            h = host.Host('https://stream.wb.ru/room/existing', d)
            class Join:
                async def click(self, **kwargs): pass
            class Page:
                urls = []
                async def goto(self, url, **kwargs):
                    self.urls.append(url)
                    return type('Response', (), {'status':200})()
                async def evaluate(self, script): return 200 if script == host.REFRESH else False
                def locator(self, selector):
                    assert selector == '[data-test="join-button"]'
                    return Join()
                async def wait_for_function(self, script, **kwargs): pass
            class Context:
                async def storage_state(self): return {'cookies':[], 'origins':[]}
            p = Page()
            for _ in range(2): self.assertTrue(asyncio.run(h.join(p, Context())))
            self.assertEqual(p.urls, [h.room]*4)
    def test_healthy_room_never_rejoins(self):
        policy = host.Recovery()
        for _ in range(3000):
            self.assertFalse(policy.probe(True))

    def test_transient_failure_does_not_restart_room(self):
        policy = host.Recovery()
        self.assertFalse(policy.probe(False))
        self.assertFalse(policy.probe(False))
        self.assertFalse(policy.probe(True))
        self.assertFalse(policy.probe(False))
        self.assertFalse(policy.probe(False))
        self.assertTrue(policy.probe(False))

    def test_backoff_caps_and_resets_on_recovery(self):
        policy = host.Recovery()
        self.assertEqual([policy.retry_delay() for _ in range(9)], [5,10,20,40,80,160,300,300,300])
        policy.probe(True)
        self.assertEqual(policy.retry_delay(), 5)

    def test_atomic_state_and_empty_logout_preserves_token(self):
        with tempfile.TemporaryDirectory() as d:
            h = host.Host('https://stream.wb.ru/room/existing', d)
            host.atomic_json(h.state, {'saved':'refresh'})
            class Empty:
                async def storage_state(self): return {'cookies':[], 'origins':[]}
            asyncio.run(h.save(Empty()))
            self.assertEqual(json.loads(h.state.read_text()), {'saved':'refresh'})
            self.assertFalse(h.state.with_suffix('.tmp').exists())

    def test_invalid_room_cannot_navigate_elsewhere(self):
        for room in ['https://evil.test/room/a', 'https://stream.wb.ru/room/a/../b']:
            with self.assertRaises(ValueError): host.Host(room, '.')

    def test_rotated_token_saved_and_auth_failures_are_consecutive(self):
        with tempfile.TemporaryDirectory() as d:
            h = host.Host('https://stream.wb.ru/room/existing', d)
            class Page:
                status = 401
                async def evaluate(self, script): return self.status
            class Context:
                async def storage_state(self): return {'cookies':[{'name':'wbx-refresh','value':'rotated','domain':'.wb.ru'}], 'origins':[]}
            p, c = Page(), Context()
            for _ in range(3): asyncio.run(h.refresh(p,c))
            self.assertTrue(h.auth_needed)
            p.status = 200
            asyncio.run(h.refresh(p,c))
            self.assertFalse(h.auth_needed)
            self.assertEqual(json.loads(h.state.read_text())['cookies'][0]['value'], 'rotated')

if __name__ == '__main__': unittest.main()
