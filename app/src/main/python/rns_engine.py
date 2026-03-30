import signal
signal.signal = lambda sig, handler: None
import RNS, LXMF, os, csv, io
from LXMF import LXMRouter

def start_engine(service_obj, storage_path, radio_params_java):
    # FIX: Convert Java/Kotlin Map to native Python dictionary
    radio_params = dict(radio_params_java)
    
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # Now .get(key, default) works perfectly
    freq = radio_params.get("freq", 915000000)
    bw = radio_params.get("bw", 125000)
    tx = radio_params.get("tx", 20)
    sf = radio_params.get("sf", 7)
    cr = radio_params.get("cr", 5)

    config = f"""
[reticulum]
enable_transport = True
share_instance = Yes

[interfaces]
  [[RNode Interface]]
    type = RNodeInterface
    enabled = True
    # We point the RNode interface to the local TCP bridge
    port = tcp://127.0.0.1:8001
    frequency = {freq}
    bandwidth = {bw}
    txpower = {tx}
    spreadingfactor = {sf}
    codingrate = {cr}
    flow_control = False
"""
    with open(os.path.join(rns_dir, "config"), "w") as f:
        f.write(config)

    try:
        RNS.Reticulum(configdir=rns_dir)
        
        id_path = os.path.join(storage_path, "storage_identity")
        local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
        if not os.path.exists(id_path): local_id.to_file(id_path)
                
        router = LXMRouter(identity=local_id, storagepath=storage_path)
        router.register_delivery_callback(lambda lxm: on_lxm(lxm, service_obj))
        
        addr = RNS.hexrep(local_id.hash, False)
        service_obj.onStatusUpdate(f"RNS Online: {addr}")
    except Exception as e:
        service_obj.onStatusUpdate(f"RNS Error: {str(e)}")

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
        service_obj.onStatusUpdate(f"Data Error: {str(e)}")