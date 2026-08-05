"""Local-only MoMo create endpoint used by the P3 integration smoke test."""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        response = {
            "partnerCode": request.get("partnerCode"),
            "orderId": request.get("orderId"),
            "requestId": request.get("requestId"),
            "amount": request.get("amount"),
            "responseTime": 1721720663942,
            "message": "Successful.",
            "resultCode": 0,
            "payUrl": "https://test-payment.momo.vn/v2/gateway/pay?t=local-test",
        }
        body = json.dumps(response).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format, *_args):
        return


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 4011), Handler).serve_forever()
