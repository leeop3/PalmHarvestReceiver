import signal
signal.signal = lambda sig, handler: None

import RNS, LXMF, os, time
from LXMF import LXMessage, LXMRouter

kotlin_cb = None
router = None
local_dest = None

def start_engine(callback_obj, storage_path):
    global kotlin_cb, router, local_dest
    kotlin_cb = callback_obj
    
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # We will use the same TCP Bridge config from before
    config_data = "[reticulum]\nenable_transport = True\n\n[interfaces]\n  [[TCP Bridge]]\n    type = TCPClientInterface\n    enabled = True\n    target_host = 127.0.0.1\n    target_port = 8001"
    with open(os.path.join(rns_dir, "config"), "w") as f: f.write(config_data)

    RNS.Reticulum(configdir=rns_dir)
    
    id_path = os.path.join(storage_path, "storage_identity")
    local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
    if not os.path.exists(id_path): local_id.to_file(id_path)
            
    router = LXMRouter(identity=local_id, storagepath=storage_path)
    local_dest = router.register_delivery_identity(local_id, display_name="PalmGrader")
    
    kotlin_cb.onStatusUpdate(f"Grader Ready: {RNS.hexrep(local_id.hash, False)}")

def send_report(dest_hex, csv_data):
    try:
        dest_hash = bytes.fromhex(dest_hex)
        # Try to recall identity from mesh
        dest_id = RNS.Identity.recall(dest_hash)
        
        target = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        if dest_id is None: target.hash = dest_hash
        
        lxm = LXMessage(target, local_dest, csv_data, title="Harvest Report")
        router.handle_outbound(lxm)
        return RNS.hexrep(lxm.hash, False)
    except Exception as e:
        return str(e)