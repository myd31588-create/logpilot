"""假装是 OpenAI 兼容接口，用来验证 LLM 链路。仅用于本地联调。"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

CONTENT = json.dumps({
    "root_cause": "库存服务 Redis 连接失败，引发订单链路连锁超时",
    "evidence": [
        "Connection refused to redis 出现 2 次",
        "Connection to <*> failed after <*>ms 出现 4 次",
        "命中规则 DOWNSTREAM 与 TIMEOUT",
    ],
    "suggestions": [
        "先检查 Redis 实例存活与网络策略",
        "为库存服务连接池增加熔断与快速失败",
        "观察订单服务超时是否随库存恢复而下降",
    ],
    "confidence": 0.86,
    "need_human": False,
}, ensure_ascii=False)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            req = json.loads(raw)
        except Exception:
            req = {}
        with open("fake_llm_last_request.json", "w", encoding="utf-8") as f:
            json.dump(req, f, ensure_ascii=False, indent=2)

        resp = {
            "id": "chatcmpl-fake",
            "object": "chat.completion",
            "model": req.get("model", "fake"),
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": CONTENT},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 200, "completion_tokens": 120},
        }
        data = json.dumps(resp, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 9099), Handler).serve_forever()
