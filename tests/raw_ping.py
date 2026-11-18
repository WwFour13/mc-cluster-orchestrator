import socket
import struct

def get_raw_ping(host, port):
    try:
        s = socket.create_connection((host, port), timeout=5)
        host_bytes = host.encode('utf-8')
        packet = b'\x00\x00' + struct.pack('>B', len(host_bytes)) + host_bytes + struct.pack('>H', port) + b'\x01'
        packet = struct.pack('>B', len(packet)) + packet
        s.sendall(packet)
        s.sendall(b'\x01\x00')
        data = b''
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            data += chunk
            if len(data) > 2048:
                break
        s.close()
        return data.decode('utf-8', errors='ignore')
    except Exception as e:
        return f"Error: {e}"

import sys

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 25565

print(f"Raw ping {host}:{port}:")
print(get_raw_ping(host, port))
