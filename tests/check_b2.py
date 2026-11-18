import json
import subprocess

import os
import sys
import json
import subprocess

if len(sys.argv) >= 4:
    key_id = sys.argv[1]
    app_key = sys.argv[2]
    bucket = sys.argv[3]
else:
    cfg_path = sys.argv[1] if len(sys.argv) > 1 else 'minecraft-server/config.json'
    if not os.path.exists(cfg_path):
        print("Usage: python check_b2.py [key_id] [app_key] [bucket]  OR  python check_b2.py [config.json]")
        sys.exit(1)
    with open(cfg_path, 'r') as f:
        cfg = json.load(f)
    key_id = cfg["b2_key_id"]
    app_key = cfg["b2_application_key"]
    bucket = cfg["b2_bucket"]

b2_target = f":b2,account={key_id},key={app_key}:{bucket}"

res = subprocess.run(['minecraft-server/bin/rclone.exe', 'lsf', f'{b2_target}/server'], capture_output=True, text=True)
print("Files in server/:")
print(res.stdout)

# Check if cluster.lock is in server/
res_lock = subprocess.run(['minecraft-server/bin/rclone.exe', 'cat', f'{b2_target}/server/cluster.lock'], capture_output=True, text=True)
print("server/cluster.lock content:")
print(res_lock.stdout if res_lock.stdout else "<EMPTY / DOES NOT EXIST>")

# Check world_generation.txt in root vs in server/
res_gen_root = subprocess.run(['minecraft-server/bin/rclone.exe', 'cat', f'{b2_target}/world_generation.txt'], capture_output=True, text=True)
print("root world_generation.txt:", res_gen_root.stdout)

res_gen_server = subprocess.run(['minecraft-server/bin/rclone.exe', 'cat', f'{b2_target}/server/world_generation.txt'], capture_output=True, text=True)
print("server/ world_generation.txt:", res_gen_server.stdout)
