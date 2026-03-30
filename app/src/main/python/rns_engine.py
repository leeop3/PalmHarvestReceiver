import os, sys, platform, time, csv, io, json, signal
from types import ModuleType
import importlib.util, importlib.machinery

# --- 1. THE PLATFORM HIJACK ---
platform.system = lambda: "Linux"

# --- 2. THE MODULE MOCKS ---
def mock_module(name):
    if name not in sys.modules:
        mock = ModuleType(name)
        mock.__spec__ = importlib.machinery.ModuleSpec(name, None)
        if name == "usbserial4a": mock.get_ports_list = lambda: []
        if name == "jnius":
            class Dummy:
                def autoclass(self, n): return self
                def cast(self, x, y): return x
            mock.autoclass = lambda x: Dummy()
        if name == "usb4a":
            mock.usb = ModuleType("usb")
            mock.usb.get_usb_device_list = lambda: []
        sys.modules[name] = mock
mock_module("usbserial4a"); mock_module("usb4a"); mock_module("jnius")

# --- 3. THE NAMESPACE HIJACK (CRITICAL) ---
import RNS
# Forcefully replace the Android-specific RNodeInterface with the Standard one
try:
    import RNS.Interfaces.RNodeInterface as StandardRNode
    import RNS.Interfaces.Android.RNodeInterface as AndroidRNode
    # Overwrite the Android class with the Standard class
    RNS.Interfaces.Android.RNodeInterface.RNodeInterface = StandardRNode.RNodeInterface
    print("RNS-LOG: Namespace Hijack Successful")
except Exception as e:
    print(f"RNS-LOG: Hijack Note: {e}")

from LXMF import LXMRouter
from RNS.Interfaces.Interface import Interface

signal.signal = lambda sig, handler: None

# --- 4. ENGINE LOGIC ---
kotlin_cb = None
router = None
local_id = None

def start_engine(service_obj, storage_path, radio_params_json):
    global kotlin_cb, router, local_id
    kotlin_cb = service_obj
    
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # Clean config
    with open(os.path.join(rns_dir, "config"), "w") as f:
        f.write("[reticulum]\nenable_transport = True\n\n[interfaces]")

    try:
        RNS.Reticulum(configdir=rns_dir)
        id_path = os.path.join(storage_path, "storage_identity")
        local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
        if not os.path.exists(id_path): local_id.to_file(id_path)
                
        router = LXMRouter(identity=local_id, storagepath=storage_path)
        router.register_delivery_callback(lambda lxm: on_lxmf(lxm, service_obj))
        
        service_obj.onStatusUpdate(f"RNS Online: {RNS.hexrep(local_id.hash, False)}")
    except Exception as e:
        service_obj.onStatusUpdate(f"Init Error: {str(e)}")

def inject_rnode(radio_params_json):
    # This calls the HIJACKED driver which works perfectly with TCP
    try:
        params = json.loads(radio_params_json)
        # Use the standard interface class (which we hijacked into the namespace)
        from RNS.Interfaces.RNodeInterface import RNodeInterface
        
        ictx = {
            "name": "RNode-Bridge",
            "type": "RNodeInterface",
            "enabled": True,
            "port": "tcp://127.0.0.1:8001",
            "frequency": params.get("freq") or 915000000,
            "bandwidth": params.get("bw") or 125000,
            "txpower": params.get("tx") or 20,
            "spreadingfactor": params.get("sf") or 7,
            "codingrate": params.get("cr") or 5,
            "flow_control": False
        }
        
        ifac = RNodeInterface(RNS.Transport, ictx)
        ifac.mode = Interface.MODE_FULL
        RNS.Transport.interfaces.append(ifac)
        return f"Interface Injected: {params.get('freq')/1000000} MHz"
    except Exception as e:
        return f"Injection Error: {str(e)}"

def on_lxmf(lxm, service_obj):
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