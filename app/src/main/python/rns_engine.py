import signal
signal.signal = lambda sig, handler: None
import RNS, LXMF, os, csv, io
from LXMF import LXMRouter

kotlin_cb = None

def start_engine(callback_obj, storage_path):
    global kotlin_cb
    kotlin_cb = callback_obj
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    config = "[reticulum]\nenable_transport = True\n[interfaces]\n  [[BT Bridge]]\n    type = TCPClientInterface\n    enabled = True\n    target_host = 127.0.0.1\n    target_port = 8001"
    with open(os.path.join(rns_dir, "config"), "w") as f: f.write(config)

    RNS.Reticulum(configdir=rns_dir)
    local_id = RNS.Identity.from_file(os.path.join(storage_path, "identity")) if os.path.exists(os.path.join(storage_path, "identity")) else RNS.Identity()
    local_id.to_file(os.path.join(storage_path, "identity"))
    
    router = LXMRouter(identity=local_id, storagepath=storage_path)
    router.register_delivery_callback(on_lxm)
    kotlin_cb.onStatusUpdate(f"Receiver Address: {RNS.hexrep(local_id.hash, False)}")

def on_lxm(lxm):
    try:
        content = lxm.content.decode("utf-8")
        if "harvester_id" in content:
            f = io.StringIO(content)
            reader = csv.DictReader(f)
            for row in reader:
                kotlin_cb.onHarvestReceived(
                    row['id'], row['harvester_id'], row['block_id'],
                    int(row['ripe_bunches']), int(row['empty_bunches']),
                    float(row['latitude']), float(row['longitude']),
                    int(row['timestamp']), row.get('photo_file', "")
                )
    except Exception as e:
        print(f"Parse Error: {e}")