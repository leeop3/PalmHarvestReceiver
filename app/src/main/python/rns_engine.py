import os, sys, time, base64, platform, json, csv, io, signal, warnings
from types import ModuleType
import importlib.util, importlib.machinery

# --- 1. THE ULTIMATE MOCKS (From rnshello + Improvements) ---
warnings.filterwarnings("ignore", category=DeprecationWarning)

class Dummy:
    def __getattr__(self, name): return Dummy()
    def __call__(self, *args, **kwargs): return Dummy()
    def __len__(self): return 0

def mock_module(name):
    mock = ModuleType(name)
    mock.__spec__ = importlib.machinery.ModuleSpec(name, None)
    if name == "usbserial4a": 
        mock.serial4a = Dummy()
        mock.get_ports_list = lambda: []
    if name == "usb4a":
        mock.usb = Dummy()
    if name == "jnius":
        mock.autoclass = lambda x: Dummy()
        mock.cast = lambda x, y: Dummy()
    sys.modules[name] = mock
    return mock

mock_module("usbserial4a"); mock_module("usb4a"); mock_module("jnius")
sys.modules["usb4a.usb"] = sys.modules["usb4a"].usb

_orig_find_spec = importlib.util.find_spec
def _mock_find_spec(name, package=None):
    if name in ["usbserial4a", "jnius", "usb4a", "usb4a.usb"]: return sys.modules[name].__spec__
    return _orig_find_spec(name, package)
importlib.util.find_spec = _mock_find_spec

# --- 2. THE HIJACK ---
platform.system = lambda: "Linux"

import RNS, LXMF
from LXMF import LXMRouter
from RNS.Interfaces.Interface import Interface

# Disable signals
signal.signal = lambda sig, handler: None

# --- 3. PALM HARVEST ENGINE LOGIC ---
kotlin_cb = None
router = None
local_destination = None

def start_engine(service_obj, storage_path):
    global kotlin_cb, router, local_destination
    kotlin_cb = service_obj
    
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # Start with empty interfaces
    with open(os.path.join(rns_dir, "config"), "w") as f:
        f.write("[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]")

    RNS.Reticulum(configdir=rns_dir)
    
    id_path = os.path.join(rns_dir, "storage_identity")
    local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
    if not os.path.exists(id_path): local_id.to_file(id_path)

    router = LXMRouter(identity=local_id, storagepath=os.path.join(storage_path, ".lxmf"))
    local_destination = router.register_delivery_identity(local_id, display_name="PalmReceiver")
    router.register_delivery_callback(on_lxmf)
    
    addr = RNS.hexrep(local_destination.hash, False)
    kotlin_cb.onStatusUpdate(f"RNS Online: {addr}")

def inject_rnode(radio_params_json):
    # This uses the Manual Injection technique from your rnshello code
    try:
        params = json.loads(radio_params_json)
        # Import the class directly
        from RNS.Interfaces.RNodeInterface import RNodeInterface
        
        ictx = {
            "name": "RNode-Bridge",
            "type": "RNodeInterface",
            "interface_enabled": True,
            "outgoing": True,
            "port": "socket://127.0.0.1:8001", # High-speed socket bridge
            "frequency": int(params.get("freq")),
            "bandwidth": int(params.get("bw")),
            "txpower": int(params.get("tx")),
            "spreadingfactor": int(params.get("sf")),
            "codingrate": int(params.get("cr")),
            "flow_control": False
        }
        
        # Instantiate and manually push into Transport
        new_ifac = RNodeInterface(RNS.Transport, ictx)
        new_ifac.mode = Interface.MODE_FULL
        RNS.Transport.interfaces.append(new_ifac)
        
        # Trigger an announce to the mesh to show we are online
        time.sleep(1)
        if local_destination: local_destination.announce()
        
        return f"Radio Online: {int(params.get('freq'))/1000000} MHz"
    except Exception as e:
        return f"Link Failed: {str(e)}"

def on_lxmf(lxm):
    try:
        content = lxm.content.decode("utf-8")
        if "harvester_id" in content:
            f_io = io.StringIO(content)
            reader = csv.DictReader(f_io)
            for row in reader:
                kotlin_cb.onHarvestReceived(
                    row['id'], row['harvester_id'], row['block_id'],
                    int(row['ripe_bunches']), int(row['empty_bunches']),
                    float(row['latitude']), float(row['longitude']),
                    int(row['timestamp']), row.get('photo_file', "")
                )
    except Exception as e:
        kotlin_cb.onStatusUpdate(f"Data Error: {e}")