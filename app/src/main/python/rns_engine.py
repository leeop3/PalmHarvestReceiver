import signal
signal.signal = lambda sig, handler: None

import RNS, LXMF, os, csv, io, time
from LXMF import LXMRouter

kotlin_cb = None

def start_engine(callback_obj, storage_path):
    global kotlin_cb
    kotlin_cb = callback_obj
    
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # Create the config file for Reticulum to use the TCP Bridge
    config_data = f"""
[reticulum]
enable_transport = True
share_instance = Yes

[interfaces]
  [[TCP Bridge]]
    type = TCPClientInterface
    enabled = True
    outgoing = True
    target_host = 127.0.0.1
    target_port = 8001
"""
    with open(os.path.join(rns_dir, "config"), "w") as f:
        f.write(config_data)

    RNS.Reticulum(configdir=rns_dir)
    
    id_path = os.path.join(storage_path, "storage_identity")
    local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
    if not os.path.exists(id_path): local_id.to_file(id_path)
            
    lxm_router = LXMRouter(identity=local_id, storagepath=storage_path)
    lxm_router.register_delivery_callback(on_lxm_received)
    
    addr = RNS.hexrep(local_id.hash, False)
    kotlin_cb.onStatusUpdate(f"Engine Ready. Addr: {addr}")

def on_lxm_received(lxm):
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
                    int(row['timestamp'])
                )
    except Exception as e:
        if kotlin_cb: kotlin_cb.onStatusUpdate(f"Data Error: {str(e)}")