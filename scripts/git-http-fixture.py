#!/usr/bin/env python3
"""CI-only HTTPS smart Git fixture. Executes the actual git http-backend, not canned packs."""
import argparse
import base64
import gzip
import json
import os
import pathlib
import ssl
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('--root', required=True)
parser.add_argument('--cert', required=True)
parser.add_argument('--key', required=True)
parser.add_argument('--port', type=int, default=19443)
args = parser.parse_args()
root = pathlib.Path(args.root).resolve()
root.mkdir(parents=True, exist_ok=True)

def git(*command, cwd=None):
    return subprocess.run(['git', *command], cwd=cwd, check=True, capture_output=True)

source = root / 'seed'
git('init', '--initial-branch=main', str(source))
(source / 'content.txt').write_text('original 中文 😀\n')
git('add', 'content.txt', cwd=source)
git('-c', 'user.name=Solvit fixture', '-c', 'user.email=fixture@example.invalid',
    'commit', '-m', 'Initial remote content', cwd=source)
for name in ['roundtrip', 'conflict', 'auth', 'slow']:
    repository = root / (name + '.git')
    git('clone', '--bare', str(source), str(repository))
    git('config', 'http.receivepack', 'true', cwd=repository)

slow_started = threading.Event()
records = []
record_lock = threading.Lock()

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_):
        pass  # Never put request headers/credentials into CI logs.
    def response(self, status, data=b'', headers=None):
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
            pass  # Expected for real client cancellation.
    def do_GET(self):
        self.request_git()
    def do_POST(self):
        self.request_git()
    def body(self):
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = []
            total = 0
            while True:
                length = int(self.rfile.readline().split(b';')[0], 16)
                if length == 0:
                    while True:
                        trailer = self.rfile.readline()
                        if trailer == b'\r\n':
                            break
                        if not trailer:
                            raise EOFError('Cancelled chunked request')
                    return b''.join(chunks)
                total += length
                if total > 128 * 1024 * 1024:
                    raise ValueError('Fixture request exceeds limit')
                chunks.append(self.rfile.read(length))
                assert self.rfile.read(2) == b'\r\n'
        length = int(self.headers.get('Content-Length', '0'))
        if not 0 <= length <= 128 * 1024 * 1024:
            raise ValueError('Fixture request exceeds limit')
        return self.rfile.read(length)
    def request_git(self):
        url = urlsplit(self.path)
        if url.path == '/health':
            return self.response(200, b'OK')
        if url.path == '/control/slow-started':
            return self.response(200, json.dumps(slow_started.is_set()).encode())
        if url.path == '/control/records':
            with record_lock:
                data = json.dumps(records).encode()
            return self.response(200, data)
        if url.path.startswith('/redirect.git/'):
            return self.response(302, headers={'Location': '/roundtrip.git/info/refs?service=git-upload-pack'})
        allowed = ['/roundtrip.git/', '/conflict.git/', '/auth.git/', '/slow.git/']
        if not any(url.path.startswith(path) for path in allowed):
            return self.response(404)
        expected = 'Basic ' + base64.b64encode(b'fixture-user:fixture-secret').decode()
        authenticated = self.headers.get('Authorization') == expected
        leaked = self.headers.get('X-Workspace-Leak') is not None
        with record_lock:
            records.append({'path': url.path, 'method': self.command,
                            'authenticated': authenticated, 'workspaceHeaderLeaked': leaked})
        if leaked:
            return self.response(418, b'Workspace headers must not enter transport')
        if not authenticated:
            return self.response(401, headers={'WWW-Authenticate': 'Basic realm="Git fixture"'})
        if url.path.startswith('/slow.git/'):
            slow_started.set()
            time.sleep(30)
        data = self.body()
        if self.headers.get('Content-Encoding', '').lower() == 'gzip':
            data = gzip.decompress(data)
        environment = dict(os.environ, GIT_PROJECT_ROOT=str(root), GIT_HTTP_EXPORT_ALL='1',
                           REQUEST_METHOD=self.command, PATH_INFO=url.path, QUERY_STRING=url.query,
                           CONTENT_TYPE=self.headers.get('Content-Type', ''), CONTENT_LENGTH=str(len(data)),
                           REMOTE_USER='fixture-user', REMOTE_ADDR=self.client_address[0])
        if self.headers.get('Git-Protocol'):
            environment['HTTP_GIT_PROTOCOL'] = self.headers['Git-Protocol']
        result = subprocess.run(['git', 'http-backend'], input=data, env=environment,
                                capture_output=True, timeout=60, check=False)
        if result.returncode != 0:
            return self.response(500, b'Git backend failed')
        headers, body = result.stdout.split(b'\r\n\r\n', 1)
        status = 200
        response_headers = {}
        for line in headers.split(b'\r\n'):
            name, value = line.decode('latin1').split(':', 1)
            if name.lower() == 'status':
                status = int(value.strip().split()[0])
            else:
                response_headers[name] = value.strip()
        self.response(status, body, response_headers)

server = ThreadingHTTPServer(('0.0.0.0', args.port), Handler)
server.daemon_threads = True
tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
tls.load_cert_chain(args.cert, args.key)
server.socket = tls.wrap_socket(server.socket, server_side=True)
server.serve_forever()
