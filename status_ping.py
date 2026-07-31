import socket, struct, json, sys

HOST = "127.0.0.1"
PORT = 25565
PROTO = 765  # Minecraft 1.20.1

def varint_bytes(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            break
    return bytes(out)

def send_packet(sock, packet_id, body=b""):
    data = varint_bytes(packet_id) + body
    sock.sendall(varint_bytes(len(data)) + data)

def read_varint(sock):
    num = 0
    for i in range(5):
        b = sock.recv(1)
        if not b:
            raise EOFError("EOF reading varint")
        b = b[0]
        num |= (b & 0x7F) << (7 * i)
        if not (b & 0x80):
            break
    return num

def read_packet(sock):
    length = read_varint(sock)
    buf = b""
    while len(buf) < length:
        chunk = sock.recv(length - len(buf))
        if not chunk:
            raise EOFError("EOF reading packet")
        buf += chunk
    pid = read_varint(sock)
    return pid, buf

s = socket.create_connection((HOST, PORT), timeout=8)
s.settimeout(8)

# 1) Handshake
addr = b"localhost"
body = varint_bytes(PROTO) + varint_bytes(len(addr)) + addr + struct.pack(">H", PORT) + varint_bytes(1)
send_packet(s, 0x00, body)

# 2) Status Request
send_packet(s, 0x00, b"")

# 3) Read Status Response (id 0x00) -> string(JSON)
pid, payload = read_packet(s)
idx = 0
str_len = 0; shift = 0
while True:
    b = payload[idx]; idx += 1
    str_len |= (b & 0x7F) << shift
    if not (b & 0x80):
        break
    shift += 7
json_bytes = payload[idx:idx+str_len]
text = json_bytes.decode("utf-8", errors="replace")
print("STATUS PING OK. response length:", len(text))
try:
    d = json.loads(text)
    print("version:", d.get("version", {}).get("name"), "| players:", d.get("players", {}).get("online"), "| desc:", str(d.get("description", ""))[:60])
except Exception as e:
    print("JSON parse note:", e, "| snippet:", text[:120])
s.close()
