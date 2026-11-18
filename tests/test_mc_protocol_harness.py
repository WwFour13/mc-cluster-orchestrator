import socket
import threading
import time
import subprocess
import os
import sys
import json

LOCAL_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 25565
PUBLIC_DOMAIN = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
PUBLIC_PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 25565

server_running = True
connections_handled = 0

def write_varint(val):
    out = bytearray()
    while (val & ~0x7F) != 0:
        out.append((val & 0x7F) | 0x80)
        val >>= 7
    out.append(val & 0x7F)
    return bytes(out)

def read_varint(sock):
    res = 0
    shift = 0
    while True:
        b = sock.recv(1)
        if not b:
            return None
        byte = b[0]
        res |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            break
        shift += 7
    return res

def mock_minecraft_server():
    global connections_handled, server_running
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('127.0.0.1', LOCAL_PORT))
    s.listen(10)
    s.settimeout(1.0)
    print(f"[Mock Minecraft Server] Listening on 127.0.0.1:{LOCAL_PORT}...")

    while server_running:
        try:
            conn, addr = s.accept()
            connections_handled += 1
            print(f"[Mock Minecraft Server] >>> ACCEPTED CONNECTION #{connections_handled} from {addr} <<<")
            
            # Read handshake packet length
            pkt_len = read_varint(conn)
            if pkt_len is not None:
                pkt_data = conn.recv(pkt_len)
                print(f"[Mock Minecraft Server] Handshake received ({len(pkt_data)} bytes)")
            
            # Read status request length
            req_len = read_varint(conn)
            if req_len is not None:
                req_data = conn.recv(req_len)
            
            # Send status response
            resp_json = json.dumps({
                "version": {"name": "1.21", "protocol": 767},
                "players": {"max": 20, "online": 1},
                "description": {"text": "PlayIt Verified Cluster"}
            }).encode('utf-8')
            
            resp_pkt = bytearray()
            resp_pkt.append(0x00) # packet id
            resp_pkt.extend(write_varint(len(resp_json)))
            resp_pkt.extend(resp_json)
            
            full_resp = bytearray()
            full_resp.extend(write_varint(len(resp_pkt)))
            full_resp.extend(resp_pkt)
            
            conn.sendall(full_resp)
            print(f"[Mock Minecraft Server] Sent SLP response to {addr}")
            conn.close()
            print(f"[Mock Minecraft Server] Connection #{connections_handled} closed cleanly.")
        except socket.timeout:
            continue
        except Exception as e:
            if server_running:
                print(f"[Mock Minecraft Server] Error: {e}")
    s.close()

def ping_client(host, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(6.0)
    s.connect((host, port))
    
    # Send Handshake
    host_bytes = host.encode('utf-8')
    handshake = bytearray()
    handshake.append(0x00) # packet id
    handshake.append(0x00) # protocol varint (0)
    handshake.extend(write_varint(len(host_bytes)))
    handshake.extend(host_bytes)
    handshake.append((port >> 8) & 0xFF)
    handshake.append(port & 0xFF)
    handshake.append(0x01) # next state: status (1)
    
    pkt = bytearray()
    pkt.extend(write_varint(len(handshake)))
    pkt.extend(handshake)
    s.sendall(pkt)
    
    # Send Status Request
    s.sendall(b'\x01\x00')
    
    # Read response
    resp_len = read_varint(s)
    pkt_id = read_varint(s)
    json_len = read_varint(s)
    raw_json = s.recv(json_len)
    s.close()
    return json.loads(raw_json.decode('utf-8'))

if __name__ == "__main__":
    t_srv = threading.Thread(target=mock_minecraft_server, daemon=True)
    t_srv.start()
    time.sleep(1)

    playit_bin = os.path.abspath("minecraft-server/bin/playit.exe")
    secret_path = os.path.abspath("minecraft-server/playit.toml")

    print("[Harness] Spawning playit.exe start...")
    playit_proc = subprocess.Popen(
        [playit_bin, "--secret_path", secret_path, "--stdout", "start"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )

    tunnel_ready = threading.Event()

    def monitor_playit():
        for line in iter(playit_proc.stdout.readline, ''):
            if not line:
                break
            line_str = line.strip()
            if "UDP session confirmed" in line_str or "udp session confirmed" in line_str.lower():
                print("[Harness] >>> PLAYIT TUNNEL IS CONFIRMED ONLINE <<<")
                tunnel_ready.set()

    t_mon = threading.Thread(target=monitor_playit, daemon=True)
    t_mon.start()

    print("[Harness] Waiting for PlayIt UDP session confirmation...")
    tunnel_ready.wait(timeout=25)
    print("[Harness] Settling for 2 seconds...")
    time.sleep(2)

    success_count = 0
    total_attempts = 3

    for i in range(1, total_attempts + 1):
        print(f"\n--- [Minecraft Ping] Cycle #{i} to {PUBLIC_DOMAIN}:{PUBLIC_PORT} ---")
        try:
            t0 = time.time()
            data = ping_client(PUBLIC_DOMAIN, PUBLIC_PORT)
            dt = (time.time() - t0) * 1000
            print(f"[Client] SUCCESS in {dt:.0f}ms! MOTD: {data.get('description')}")
            success_count += 1
        except Exception as e:
            print(f"[Client] Cycle #{i} FAILED: {e}")
        time.sleep(1)

    print("\n========================================================")
    print(f"[Harness Summary] Results: {success_count}/{total_attempts} verified Minecraft SLP handshakes.")
    print("========================================================")

    server_running = False
    playit_proc.terminate()
    try:
        playit_proc.wait(timeout=3)
    except:
        playit_proc.kill()

    sys.exit(0 if success_count == total_attempts else 1)
