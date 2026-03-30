import signal
signal.signal = lambda sig, handler: None
import RNS, LXMF, os, csv, io
from LXMF import LXMRouter

def start_engine(service_obj, storage_path):
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # Simple bridge config
    config = "[reticulum]\nenable_transport = True\n[interfaces]\n  [[BT Bridge]]\n    type = TCPClientInterface\n    enabled = True\n    target_host = 127.0.0.1\n    target_port = 8001"
    with open(os.path.join(rns_dir, "config"), "w") as f: f.write(config)

    RNS.Reticulum(configdir=rns_dir)
    local_id = RNS.Identity() # Persistent ID handling here...
    
    router = LXMRouter(identity=local_id, storagepath=storage_path)
    router.register_delivery_callback(lambda lxm: on_lxm(lxm, service_obj))
    service_obj.onStatusUpdate(f"RNS Online: {RNS.hexrep(local_id.hash, False)}")

def on_lxm(lxm, service_obj):
    try:
        content = lxm.content.decode("utf-8")
        if "harvester_id" in content:
            f = io.StringIO(content)
            reader = csv.DictReader(f)
            for row in reader:
                service_obj.onHarvestReceived(
                    row['id'], row['harvester_id'], row['block_id'],
                    int(row['ripe_bunches']), int(row['empty_bunches']),
                    float(row['latitude']), float(row['longitude']),
                    int(row['timestamp']), row.get('photo_file', "")
                )
    except Exception as e:
        service_obj.onStatusUpdate(f"Parse Error: {e}")