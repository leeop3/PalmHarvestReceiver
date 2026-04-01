import os, sys, time, json, csv, io, signal, warnings
from types import ModuleType
import importlib.util, importlib.machinery

warnings.filterwarnings("ignore", category=DeprecationWarning)

# --- MOCKS ---
class Dummy:
    def __init__(self, n="D"): self.__name__=n; self.__spec__=importlib.machinery.ModuleSpec(n,None)
    def __getattr__(self, n): return self
    def __call__(self, *a, **k): return self
    def __len__(self): return 0
    def __getitem__(self, i): return self
def mock_module(name):
    mock = Dummy(name); sys.modules[name] = mock; return mock
mock_module("usbserial4a"); mock_module("usb4a"); mock_module("jnius")
sys.modules["usb4a.usb"] = sys.modules["usb4a"]

_orig_find_spec = importlib.util.find_spec
def _mock_find_spec(name, package=None):
    if name in["usbserial4a", "jnius", "usb4a", "usb4a.usb"]: return sys.modules[name].__spec__
    return _orig_find_spec(name, package)
importlib.util.find_spec = _mock_find_spec

# --- RNS ---
import RNS
from LXMF import LXMRouter
from RNS.Interfaces.Android.RNodeInterface import RNodeInterface
from RNS.Interfaces.Interface import Interface

signal.signal = lambda sig, handler: None
kotlin_cb = None
local_destination = None

def start_engine(service_obj, storage_path, radio_params_json=None):
    global kotlin_cb, local_destination
    kotlin_cb = service_obj
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    with open(os.path.join(rns_dir, "config"), "w") as f:
        f.write("[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]\n")
    try:
        RNS.Reticulum(configdir=rns_dir)
        id_path = os.path.join(rns_dir, "storage_identity")
        local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
        if not os.path.exists(id_path): local_id.to_file(id_path)
        router = LXMRouter(identity=local_id, storagepath=os.path.join(storage_path, ".lxmf"))
        local_destination = router.register_delivery_identity(local_id, display_name="PalmReceiver")
        router.register_delivery_callback(on_lxmf)
        kotlin_cb.onStatusUpdate(f"RNS Online: {RNS.hexrep(local_destination.hash, False)}")
    except Exception as e:
        kotlin_cb.onStatusUpdate(f"Init Error: {str(e)}")

def inject_rnode(radio_params_json):
    try:
        # 1. CLEAN UP EXISTING INTERFACES (Anti-Bridge Error logic)
        to_remove = []
        for ifac in RNS.Transport.interfaces:
            if ifac.name == "Android RNode Bridge":
                to_remove.append(ifac)
        
        for ifac in to_remove:
            try:
                ifac.detach() # Stops the hardware thread
                RNS.Transport.interfaces.remove(ifac)
            except: pass

        # 2. INJECT NEW INTERFACE
        params = json.loads(radio_params_json)
        ictx = {
            "name": "Android RNode Bridge",
            "type": "RNodeInterface",
            "interface_enabled": True,
            "outgoing": True,
            "tcp_host": "127.0.0.1",
            "tcp_port": 7633,
            "frequency": int(params.get("freq")),
            "bandwidth": int(params.get("bw")),
            "txpower": int(params.get("tx")),
            "spreadingfactor": int(params.get("sf")),
            "codingrate": int(params.get("cr")),
            "flow_control": False
        }
        
        new_ifac = RNodeInterface(RNS.Transport, ictx)
        new_ifac.IN = True
        new_ifac.OUT = True
        RNS.Transport.interfaces.append(new_ifac)
        
        time.sleep(0.5)
        if local_destination: local_destination.announce()
        return "RNode Reconnected Successfully"
    except Exception as e:
        return f"Link Error: {str(e)}"

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
        if kotlin_cb: kotlin_cb.onStatusUpdate(f"Data Error: {str(e)}")