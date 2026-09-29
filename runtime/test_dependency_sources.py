import http.server
import threading
import unittest

from check_dependency_sources import check_sources


class DependencySourceTest(unittest.TestCase):
    def test_missing_locked_source_fails_without_downloading_payloads(self):
        requests = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_HEAD(self):
                requests.append(('HEAD', self.path))
                self.send_response(200 if self.path == '/available' else 404)
                self.end_headers()

            def log_message(self, *_):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        base = f'http://127.0.0.1:{server.server_port}'
        try:
            check_sources([{'name': 'available', 'url': base + '/available'}])
            with self.assertRaisesRegex(RuntimeError, 'removed: HTTP 404'):
                check_sources([{'name': 'removed', 'url': base + '/removed'}])
            self.assertEqual([('HEAD', '/available'), ('HEAD', '/removed')], requests)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()
