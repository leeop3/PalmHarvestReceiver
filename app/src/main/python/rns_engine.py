import RNS, LXMF, os, csv, io
from LXMF import LXMRouter

kotlin_cb = None

def start_engine(callback_obj, storage_path):
    global kotlin_cb
    kotlin_cb = callback_obj
    
    # 1. Setup RNS Directories
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # 2. Initialize Reticulum
    # We start with a default config; you will later add the RNode interface here
    RNS.Reticulum(configdir=rns_dir)
    
    # 3. Setup LXMF
    local_id = RNS.Identity()
    lxm_router = LXMRouter(identity=local_id, storagepath=storage_path)
    lxm_router.register_delivery_callback(on_lxm_received)
    
    kotlin_cb.onStatusUpdate("RNS Online. Waiting for Data...")

def on_lxm_received(lxm):
    try:
        content = lxm.content.decode("utf-8")
        # Process CSV Harvest Data
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
        kotlin_cb.onStatusUpdate(f"Error: {str(e)}")