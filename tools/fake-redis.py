"""最小 RESP 服务，用来在没有 Redis 的环境里验证去重/锁/缓存链路。仅用于本地联调。"""
import socket
import threading
import time

STORE = {}
LOCK = threading.Lock()


def encode_bulk(s):
    b = s.encode("utf-8")
    return b"$" + str(len(b)).encode() + b"\r\n" + b + b"\r\n"


def read_command(f):
    line = f.readline()
    if not line:
        return None
    if not line.startswith(b"*"):
        return None
    n = int(line[1:].strip())
    args = []
    for _ in range(n):
        hdr = f.readline()
        if not hdr.startswith(b"$"):
            return None
        ln = int(hdr[1:].strip())
        data = f.read(ln)
        f.read(2)
        args.append(data.decode("utf-8", "replace"))
    return args


def handle(conn):
    f = conn.makefile("rb")
    try:
        while True:
            args = read_command(f)
            if args is None:
                break
            cmd = args[0].upper()
            now = time.time()

            if cmd == "PING":
                conn.sendall(b"+PONG\r\n")

            elif cmd == "HELLO":
                # 回错误，迫使客户端退回 RESP2
                conn.sendall(b"-ERR unknown command 'HELLO'\r\n")

            elif cmd == "CLIENT":
                conn.sendall(b"+OK\r\n")

            elif cmd == "COMMAND":
                conn.sendall(b"*0\r\n")

            elif cmd == "INFO":
                conn.sendall(encode_bulk("redis_version:7.0.0-fake\r\n"))

            elif cmd == "SET":
                key, val = args[1], args[2]
                nx = any(a.upper() == "NX" for a in args[3:])
                ttl = None
                for i, a in enumerate(args):
                    if a.upper() in ("EX", "PX") and i + 1 < len(args):
                        ttl = float(args[i + 1]) * (1 if a.upper() == "EX" else 0.001)
                with LOCK:
                    expired = key in STORE and STORE[key][1] is not None and STORE[key][1] <= now
                    exists = key in STORE and not expired
                    if nx and exists:
                        conn.sendall(b"$-1\r\n")
                    else:
                        STORE[key] = (val, now + ttl if ttl else None)
                        conn.sendall(b"+OK\r\n")

            elif cmd == "GET":
                key = args[1]
                with LOCK:
                    item = STORE.get(key)
                    if item and item[1] is not None and item[1] <= now:
                        del STORE[key]
                        item = None
                conn.sendall(b"$-1\r\n" if not item else encode_bulk(item[0]))

            elif cmd == "DEL":
                removed = 0
                with LOCK:
                    for k in args[1:]:
                        if k in STORE:
                            del STORE[k]
                            removed += 1
                conn.sendall(b":" + str(removed).encode() + b"\r\n")

            elif cmd == "EXISTS":
                with LOCK:
                    n = sum(1 for k in args[1:] if k in STORE)
                conn.sendall(b":" + str(n).encode() + b"\r\n")

            else:
                conn.sendall(b"+OK\r\n")
    except Exception:
        pass
    finally:
        try:
            conn.close()
        except Exception:
            pass


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 6380))
    srv.listen(50)
    print("fake redis on 6380", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
