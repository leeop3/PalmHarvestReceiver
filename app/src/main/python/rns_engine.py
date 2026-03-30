import sys, os, csv, io, json, signal
from types import ModuleType

# --- ANDROID MOCKS ---
mock_usb = ModuleType("usbserial4a")
mock_usb.serial4a = ModuleType("serial4a")
mock_usb.get_ports_list = lambda: []
sys.modules["usbserial4a"] = mock_usb
mock_jnius = ModuleType("jnius")
mock_jnius.autoclass = lambda x: None
sys.modules["jnius"] = mock_jnius

signal.signal = lambda sig, handler: None
import RNS, LXMF
from LXMF import LXMRouter

def start_engine(service_obj, storage_path, radio_params_json):
    # 1. Parse the JSON string into a native Python Dictionary
    # This completely eliminates the "LinkedHashMap not iterable" error.
    params = json.loads(radio_params_json)
    
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    f  = params.get("freq", 915000000)
    bw = params.get("bw", 125000)
    tx = params.get("tx", 20)
    sf = params.get("sf", 7)
    cr = params.get("cr", 5)

    config = f"""
[reticulum]
enable_transport = True
share_instance = Yes

[interfaces]
  [[RNode Interface]]
    type = RNodeInterface
    enabled = True
    port = tcp://127.0.0.1:8001
    frequency = {f}
    bandwidth = {bw}
    txpower = {tx}
    spreadingfactor = {sf}
    codingrate = {cr}
    flow_control = False
"""
    with open(os.path.join(rns_dir, "config"), "w") as f_out:
        f_out.write(config)

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
            f_io = io.StringIO(content)
            reader = csv.DictReader(f_io)
            for row in reader:
                service_obj.onHarvestReceived(
                    row['id'], row['harvester_id'], row['block_id'],
                    int(row['ripe_bunches']), int(row['empty_bunches']),
                    float(row['latitude']), float(row['longitude']),
                    int(row['timestamp']), row.get('photo_file', "")
                )
    except Exception as e:
        service_obj.onStatusUpdate(f"Data Error: {str(e)}")