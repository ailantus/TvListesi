#!/usr/bin/env python3
"""
Örnek kanal listesi sunucusu.

Kullanım:
    python3 baslat.py            # 8000 portu
    python3 baslat.py 8080       # başka port

Telefon veya TV ile aynı Wi-Fi ağında olmalıdır.
"""
import http.server
import os
import socket
import socketserver
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
ROOT = os.path.dirname(os.path.abspath(__file__))


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def log_message(self, fmt, *args):
        print("  " + fmt % args)


def local_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


if __name__ == "__main__":
    os.chdir(ROOT)
    ip = local_ip()

    print("Kanal listesi sunucusu çalışıyor:\n")
    print(f"  Bilgisayar : http://localhost:{PORT}/channels.json")
    print(f"  Telefon/TV : http://{ip}:{PORT}/channels.json")
    print(f"  M3U örneği : http://{ip}:{PORT}/channels.m3u")
    print("\nDurdurmak için Ctrl+C\n")

    socketserver.ThreadingTCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("", PORT), Handler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nDurduruldu.")
