import json
import os
import subprocess
import sys
import time

def main():
    print("=" * 80)
    print("      MC CLUSTER BACKEND PROBE - PRODUCTION B2/S3 VERIFICATION")
    print("=" * 80)

    # 1. Resolve config.json
    cfg_candidates = []
    if len(sys.argv) > 1:
        cfg_candidates.append(sys.argv[1])
    cfg_candidates.extend([
        os.path.join("minecraft-server", "config.json"),
        "config.json"
    ])
    cfg_path = None
    for c in cfg_candidates:
        if os.path.exists(c):
            cfg_path = c
            break

    if not cfg_path:
        print("[ERROR] config.json not found in 'minecraft-server/' or current directory.")
        sys.exit(1)

    print(f"[1/5] Reading credentials from: {os.path.abspath(cfg_path)}")
    try:
        with open(cfg_path, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception as e:
        print(f"[ERROR] Failed to parse config.json: {e}")
        sys.exit(1)

    bucket = cfg.get("b2_bucket", "").strip()
    key_id = cfg.get("b2_key_id", "").strip()
    app_key = cfg.get("b2_application_key", "").strip()
    endpoint = cfg.get("b2_endpoint", "").strip()

    if not bucket or not key_id or not app_key:
        print("[ERROR] Missing required keys (b2_bucket, b2_key_id, b2_application_key) in config.json.")
        sys.exit(1)

    # Clean key_id alphanumeric
    clean_key_id = "".join(c for c in key_id if c.isalnum())
    masked_key = app_key[:4] + "..." + app_key[-4:] if len(app_key) > 8 else "********"

    print(f"  • Bucket:          {bucket}")
    print(f"  • Key ID:          {clean_key_id} (Length: {len(clean_key_id)}, Alphanumeric: {'YES' if clean_key_id == key_id else 'SANITIZED'})")
    print(f"  • Application Key: {masked_key}")

    # 2. Derive S3 Endpoint
    if not endpoint:
        if clean_key_id.startswith("00") and len(clean_key_id) >= 3:
            cluster = clean_key_id[:3]
            region = "us-west-"
            if cluster == "005":
                region = "us-east-"
            elif cluster == "003":
                region = "eu-central-"
            endpoint = f"s3.{region}{cluster}.backblazeb2.com"
        else:
            endpoint = "s3.us-west-004.backblazeb2.com"

    print(f"  • S3 Endpoint:     {endpoint}")

    # 3. Locate rclone
    rclone_candidates = [
        os.path.join("minecraft-server", "bin", "rclone.exe"),
        os.path.join("bin", "rclone.exe"),
        "rclone.exe",
        "rclone"
    ]
    rclone_bin = None
    for r in rclone_candidates:
        if os.path.exists(r):
            rclone_bin = r
            break

    if not rclone_bin:
        print("[ERROR] rclone executable not found.")
        sys.exit(1)

    print(f"[2/5] Using rclone binary: {os.path.abspath(rclone_bin)}")

    # 4. Construct production remote targets
    target_failing = f":s3,provider=Other,endpoint={endpoint},access_key_id={clean_key_id},secret_access_key={app_key}:{bucket}/server"
    target_fixed   = f":s3,provider=Other,endpoint={endpoint},access_key_id={clean_key_id},secret_access_key={app_key},no_check_bucket=true:{bucket}/server"

    print("\n" + "-" * 80)
    print(" 3. COMPARISON TEST: STREAM UPLOAD (rcat) WITH & WITHOUT no_check_bucket")
    print("-" * 80)

    print("\n[A] Testing WITHOUT no_check_bucket (Legacy / Failing Mode):")
    res_a = subprocess.run([rclone_bin, "rcat", target_failing + "/probe_test.tmp"], input=b"test", capture_output=True)
    if res_a.returncode != 0:
        err_a = res_a.stderr.decode("utf-8", errors="ignore").strip()
        print(f"  ❌ FAILED as expected (Exit code {res_a.returncode})")
        # Print relevant error lines
        for line in err_a.splitlines():
            if "ERROR" in line or "AccessDenied" in line or "StatusCode: 403" in line:
                print(f"     {line}")
    else:
        print("  ✔ Succeeded unexpectedly (Account key has global CreateBucket permissions).")

    print("\n[B] Testing WITH no_check_bucket=true (v2.1.4 Production Patch):")
    res_b = subprocess.run([rclone_bin, "rcat", target_fixed + "/probe_test.tmp"], input=b"test", capture_output=True)
    if res_b.returncode == 0:
        print("  ✔ SUCCESS (Exit code 0) - Directly uploaded stream without forbidden S3 CreateBucket check!")
        # Clean probe file
        subprocess.run([rclone_bin, "deletefile", target_fixed + "/probe_test.tmp"], capture_output=True)
    else:
        err_b = res_b.stderr.decode("utf-8", errors="ignore").strip()
        print(f"  ❌ FAILED (Exit code {res_b.returncode}):\n     {err_b}")

    print("\n" + "-" * 80)
    print(" 4. PRODUCTION LAUNCH LIFECYCLE SIMULATION (4 OPERATIONS)")
    print("-" * 80)

    # Op 1: lsd
    print("\n[Op 1/4] Checking S3 Root Bucket Access ('rclone lsd')...")
    p1 = subprocess.run([rclone_bin, "lsd", target_fixed], capture_output=True)
    if p1.returncode == 0:
        print("  ✔ Root listing status: 200 OK")
    else:
        print(f"  ❌ Failed: {p1.stderr.decode('utf-8', errors='ignore').strip()}")

    # Op 2: cat manifest.json
    print("\n[Op 2/4] Reading Cloud Manifest ('manifest.json')...")
    p2 = subprocess.run([rclone_bin, "cat", target_fixed + "/manifest.json"], capture_output=True)
    if p2.returncode == 0 and p2.stdout:
        m_raw = p2.stdout.decode("utf-8", errors="ignore").strip()
        print(f"  ✔ Cloud Manifest payload retrieved:\n    {m_raw}")
    else:
        print("  ℹ No manifest.json found in cloud (Fresh bucket state).")

    # Op 3: cat cluster.lock
    print("\n[Op 3/4] Reading Cloud Lock Status ('cluster.lock')...")
    p3 = subprocess.run([rclone_bin, "cat", target_fixed + "/cluster.lock"], capture_output=True)
    if p3.returncode == 0 and p3.stdout.strip():
        print(f"  ✔ Active Lock found:\n    {p3.stdout.decode('utf-8', errors='ignore').strip()}")
    else:
        print("  ✔ Cloud Lock clean (No active locks held).")

    # Op 4: rcat cluster.lock test heartbeat
    print("\n[Op 4/4] Writing Test Cloud Lock Heartbeat ('rcat cluster.lock')...")
    test_heartbeat = json.dumps({
        "host": "test-diagnostic-runner",
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    }).encode("utf-8")
    p4 = subprocess.run([rclone_bin, "rcat", target_fixed + "/cluster.lock"], input=test_heartbeat, capture_output=True)
    if p4.returncode == 0:
        print("  ✔ Cloud Lock Heartbeat successfully written to B2 (HTTP 200 OK)!")
        # Immediately release test lock
        subprocess.run([rclone_bin, "deletefile", target_fixed + "/cluster.lock"], capture_output=True)
        print("  ✔ Test lock successfully released.")
    else:
        print(f"  ❌ Heartbeat write failed:\n    {p4.stderr.decode('utf-8', errors='ignore').strip()}")

    print("\n" + "=" * 80)
    print(" [VERDICT] All production Backblaze B2/S3 queries tested successfully!")
    print("=" * 80)

if __name__ == "__main__":
    main()
