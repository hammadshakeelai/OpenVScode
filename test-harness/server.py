#!/usr/bin/env python3
"""
OpenVScode Mobile Simulator Test Harness Server
Serves the simulator web application locally on port 8085.
"""

import http.server
import socketserver
import os
import sys

PORT = 8085
DIRECTORY = os.path.dirname(os.path.abspath(__file__))

class HarnessHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def end_headers(self):
        # Enable CORS and disable aggressive caching for test runs
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        super().end_headers()

    def log_message(self, format, *args):
        # Print concise request log
        sys.stdout.write(f"[Harness Server] {self.address_string()} - {format % args}\n")
        sys.stdout.flush()

def run_server():
    os.chdir(DIRECTORY)
    # Allow socket address reuse to prevent 'Address already in use' errors
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("", PORT), HarnessHandler) as httpd:
        print(f"==================================================")
        print(f" OpenVScode Mobile Simulator Server Active")
        print(f" Serving directory: {DIRECTORY}")
        print(f" URL: http://127.0.0.1:{PORT}/index.html")
        print(f"==================================================")
        sys.stdout.flush()
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server.")
            httpd.server_close()

if __name__ == "__main__":
    run_server()
