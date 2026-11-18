import socket
import struct
import json

def ping_minecraft_server(host, port=25565, timeout=5):
    try:
        s = socket.create_connection((host, port), timeout=timeout)
        # Handshake packet
        host_bytes = host.encode('utf-8')
        packet = b'\x00\x00' + struct.pack('>B', len(host_bytes)) + host_bytes + struct.pack('>H', port) + b'\x01'
        packet = struct.pack('>B', len(packet)) + packet
        s.sendall(packet)

        # Status request packet
        s.sendall(b'\x01\x00')

        # Read response
        resp_len = s.recv(2)
        packet_id = s.recv(1)
        # Read payload
        data = b''
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            data += chunk
            if b'}' in chunk:
                break
        s.close()
        
        # Find json in data
        idx = data.find(b'{')
        if idx != -1:
            raw_json = data[idx:data.rfind(b'}')+1].decode('utf-8', errors='ignore')
            return json.loads(raw_json)
        return {"raw": data.decode('utf-8', errors='ignore')}
    except Exception as e:
        return {"error": str(e)}

import sys
import subprocess

address = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 25565

# Also resolve DNS
try:
    ip = socket.gethostbyname(address)
    print(f"Resolved {address} to {ip}")
except Exception as e:
    print(f"Failed to resolve {address}: {e}")

print(f"Pinging Minecraft server at {address}:{port}...")
res = ping_minecraft_server(address, port)
print(f"Result ({port}):", res)

# Check SRV record
srv_res = subprocess.run(["nslookup", "-type=srv", f"_minecraft._tcp.{address}"], capture_output=True, text=True)
print("SRV record lookup:\n", srv_res.stdout)
