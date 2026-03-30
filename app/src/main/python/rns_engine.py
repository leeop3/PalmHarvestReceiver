import sys, os, csv, io, json, signal, time
from types import ModuleType
import importlib.util, importlib.machinery

# --- THE MOCK FIXES ---
class Dummy:
    def __init__(self, name):
        self.__name__ = name
        self.__spec__ = importlib.machinery.ModuleSpec(name, None)
    def __getattr__(self, name): return self
    def __call__(self, *args, **kwargs): return self
    def __len__(self): return 0
    def __getitem__(self, index): return self

def mock_module(name):
    mock = Dummy(name)
    sys.modules[name] = mock
    return mock

usbserial_mock = mock_module("usbserial4a")
usbserial_mock.serial4a = Dummy("serial4a")
jnius_mock = mock_module("jnius")
jnius_mock.autoclass = lambda x: Dummy("DummyClass")
jnius_mock.cast = lambda x, y: x
usb4a_mock = mock_module("usb4a")
usb4a_inner = Dummy("usb4a.usb") 
usb4a_mock.usb = usb4a_inner
sys.modules["usb4a.usb"] = usb4a_inner

_orig_find_spec = importlib.util.find_spec
def _mock_find_spec(name, package=None):
    if name in ["usbserial4a", "jnius", "usb4a", "usb4a.usb"]:
        return sys.modules[name].__spec__
    return _orig_find_spec(name, package)
importlib.util.find_spec = _mock_find_spec

# --- RNS ENGINE ---
signal.signal = lambda sig, handler: None
import RNS, LXMF
from LXMF import LXMRouter
from RNS.Interfaces.Android.RNodeInterface import RNodeInterface

kotlin_cb = None
router = None
local_id = None
storage = None

def start_engine(service_obj, storage_path, radio_params_json):
    global kotlin_cb, router, local_id, storage
    kotlin_cb = service_obj
    storage = storage_path
    
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # Start with NO interfaces in config to prevent the reconnect loop
    config = "[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]"
    with open(os.path.join(rns_dir, "config"), "w") as f_out:
        f_out.write(config)

    try:
        RNS.Reticulum(configdir=rns_dir)
        id_path = os.path.join(storage_path, "storage_identity")
        local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
        if not os.path.exists(id_path): local_id.to_file(id_path)
                
        router = LXMRouter(identity=local_id, storagepath=storage_path)
        router.register_delivery_callback(lambda lxm: on_lxmf(lxm, service_obj))
        
        addr = RNS.hexrep(local_id.hash, False)
        service_obj.onStatusUpdate(f"RNS Online: {addr}")
    except Exception as e:
        service_obj.onStatusUpdate(f"RNS Error: {str(e)}")

def inject_rnode(radio_params_json):
    # This is called by Kotlin AFTER the Bluetooth bridge is ready
    params = json.loads(radio_params_json)
    try:
        ictx = {
            "name": "RNode BT Bridge",
            "type": "RNodeInterface",
            "enabled": True,
            "port": "tcp://127.0.0.1:8001",
            "frequency": params.get("freq", 915000000),
            "bandwidth": params.get("bw", 125000),
            "txpower": params.get("tx", 20),
            "spreadingfactor": params.get("sf", 7),
            "codingrate": params.get("cr", 5),
            "flow_control": False
        }
        
        # Manually create and register the interface
        ifac = RNodeInterface(RNS.Transport, ictx)
        RNS.Transport.interfaces.append(ifac)
        return "Interface Injected"
    except Exception as e:
        return f"Injection Failed: {str(e)}"

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