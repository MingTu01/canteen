# -*- coding: utf-8 -*-
"""
网络扫码设备接收服务(测试用)
================================
适用于网络中「扫码后上报服务器」的收银/支付类扫码枪/扫码引擎
(如新大陆、斑马、精锐等网络扫码设备)。

用法:
    python network_scan_receiver.py [端口]    默认 8091

设备配置:
    把网络扫码枪/扫码设备的「扫描后发送 URL」配置为:
        http://<本机IP>:8091/scan?data=%s
    (%s 为扫码内容占位符,部分设备用 {code} / {data} 等,按设备手册调整)

接收支持:
    GET  /scan?data=<内容>          扫码内容在 query 上
    GET  /scan?code=<内容>          部分设备用 code
    POST /scan  body 为 JSON 如 {"data":"<内容>"} 或 {"code":"<内容>"}
    POST /scan  body 为表单 data=<内容> 或纯文本内容

推送支持(浏览器测试页):
    GET  /events   SSE 长连接,把收到的扫码内容实时推送到测试页
"""
import json
import queue
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8091

# SSE 订阅者队列列表(受锁保护)
subscribers = []
sub_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    # ---------- 解析扫码内容 ----------
    def _extract(self, query=None, body=None):
        data = None
        if query:
            data = query.get('data', [None])[0] or query.get('code', [None])[0] \
                or query.get('content', [None])[0]
        if not data and body:
            text = body.decode('utf-8', 'ignore').strip()
            if text.startswith('{'):
                try:
                    j = json.loads(text)
                    data = j.get('data') or j.get('code') or j.get('content') or text
                except Exception:
                    data = text
            elif '=' in text:
                p = parse_qs(text)
                data = p.get('data', [text])[0] if 'data' in p else text
            else:
                data = text
        return (data or '').strip()

    # ---------- 广播给所有 SSE 订阅者 ----------
    def _broadcast(self, data):
        msg = json.dumps({'data': data}, ensure_ascii=False)
        with sub_lock:
            dead = []
            for q in subscribers:
                try:
                    q.put_nowait(msg)
                except queue.Full:
                    dead.append(q)
            for q in dead:
                subscribers.remove(q)

    # ---------- 处理扫码上报 ----------
    def do_POST(self):
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b''
        query = parse_qs(urlparse(self.path).query)
        data = self._extract(query, body)
        self._broadcast(data)
        self._send_json(200, {'code': 200, 'message': 'ok', 'data': data})

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == '/events':
            self._sse()
            return
        query = parse_qs(parsed.query)
        data = self._extract(query)
        self._broadcast(data)
        self._send_json(200, {'code': 200, 'message': 'ok', 'data': data})

    # ---------- SSE 长连接 ----------
    def _sse(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        q = queue.Queue(maxsize=100)
        with sub_lock:
            subscribers.append(q)
        try:
            while True:
                try:
                    msg = q.get(timeout=15)
                    self.wfile.write(('data: ' + msg + '\n\n').encode('utf-8'))
                    self.wfile.flush()
                except queue.Empty:
                    # 心跳保活
                    self.wfile.write(b': ping\n\n')
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        finally:
            with sub_lock:
                if q in subscribers:
                    subscribers.remove(q)

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == '__main__':
    server = ThreadingHTTPServer(('0.0.0.0', PORT), Handler)
    print('网络扫码接收服务已启动: http://0.0.0.0:%d' % PORT)
    print('把网络扫码枪/扫码设备的"扫描后发送URL"配置为:')
    print('    http://<本机IP>:%d/scan?data=%%s' % PORT)
    print('浏览器测试页连接 /events(SSE) 即可实时收到扫码内容。')
    print('按 Ctrl+C 停止。')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\n已停止。')