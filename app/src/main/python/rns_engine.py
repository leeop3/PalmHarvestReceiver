import signal
# FIX: Monkey-patch signal.signal before importing RNS
# This prevents the "signal only works in main thread" error on Android
original_signal = signal.signal
signal.signal = lambda sig, handler: None

import RNS, LXMF, os, csv, io
from LXMF import LXMRouter

kotlin_cb = None

def start_engine(callback_obj, storage_path):
    global kotlin_cb
    kotlin_cb = callback_obj
    
    try:
        # 1. Setup RNS Directories
        rns_dir = os.path.join(storage_path, ".reticulum")
        if not os.path.exists(rns_dir): os.makedirs(rns_dir)
        
        # 2. Initialize Reticulum 
        # RNS will now call our "fake" signal function and won't crash
        RNS.Reticulum(configdir=rns_dir)
        
        # 3. Setup LXMF
        # We use a persistent identity file so the address doesn't change every time
        id_path = os.path.join(storage_path, "storage_identity")
        if os.path.exists(id_path):
            local_id = RNS.Identity.from_file(id_path)
        else:
            local_id = RNS.Identity()
            local_id.to_file(id_path)
            
        lxm_router = LXMRouter(identity=local_id, storagepath=storage_path)
        lxm_router.register_delivery_callback(on_lxm_received)
        
        # Tell Kotlin we are ready
        addr = RNS.hexrep(local_id.hash, False)
        kotlin_cb.onStatusUpdate(f"RNS Online. Address: {addr}")
        
    except Exception as e:
        if kotlin_cb:
            kotlin_cb.onStatusUpdate(f"Engine Error: {str(e)}")

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
        if kotlin_cb:
            kotlin_cb.onStatusUpdate(f"Data Error: {str(e)}")