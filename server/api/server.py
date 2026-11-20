import json
import threading
from urllib.parse import urlsplit, parse_qs
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DATA_FILE = Path("/data/transcripts.ndjson")
LOCK = threading.Lock()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def response(self, status, body):
        encoded = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        if self.path == "/health":
            return self.response(200, {"status": "ok"})
        if urlsplit(self.path).path == "/v1/transcripts":
            rows = [json.loads(line) for line in DATA_FILE.read_text(encoding="utf-8").splitlines()] if DATA_FILE.exists() else []
            room_id = parse_qs(urlsplit(self.path).query).get("meetingRoomId", [None])[0]
            if room_id:
                rows = [row for row in rows if row.get("meetingRoomId") == room_id]
            return self.response(200, {"items": rows})
        return self.response(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/v1/transcripts":
            return self.response(404, {"error": "not_found"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size < 1 or size > 1024 * 1024:
                return self.response(413, {"error": "invalid_size"})
            payload = json.loads(self.rfile.read(size))
            if not isinstance(payload, dict) or not payload.get("clientTranscriptId"):
                raise ValueError("clientTranscriptId is required")
            if not isinstance(payload.get("segments"), list) or not payload["segments"]:
                raise ValueError("segments required")
            if any(not isinstance(s, dict) or not s.get("segmentId") or not isinstance(s.get("text"), str) for s in payload["segments"]):
                raise ValueError("invalid segment")
        except (ValueError, json.JSONDecodeError):
            return self.response(400, {"error": "invalid_transcript"})
        DATA_FILE.parent.mkdir(parents=True, exist_ok=True)
        payload["receivedAt"] = datetime.now(timezone.utc).isoformat()
        with LOCK:
            existing = [json.loads(line) for line in DATA_FILE.read_text(encoding="utf-8").splitlines()] if DATA_FILE.exists() else []
            seen = {(row.get("clientTranscriptId"), s.get("segmentId")) for row in existing for s in row.get("segments", [])}
            payload["segments"] = [s for s in payload["segments"] if (payload["clientTranscriptId"], s["segmentId"]) not in seen]
            if payload["segments"]:
                with DATA_FILE.open("a", encoding="utf-8") as output:
                    output.write(json.dumps(payload, ensure_ascii=False) + "\n")
        return self.response(201, {"accepted": True})

if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
