import sys, os, csv, io, json, signal, warnings, shutil, traceback, platform
from types import ModuleType
import importlib.util, importlib.machinery
from datetime import datetime

warnings.filterwarnings("ignore", category=DeprecationWarning)
warnings.filterwarnings("ignore", category=ResourceWarning)

# --- 1. THE ULTIMATE ANDROID MOCKS (LOCKED) ---
class Dummy:
    def __init__(self, name="Dummy"):
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

mock_module("usbserial4a").serial4a = Dummy("serial4a")
mock_module("jnius").autoclass = lambda x: Dummy("DummyClass")
mock_module("usb4a").usb = Dummy("usb4a.usb")
sys.modules["usb4a.usb"] = sys.modules["usb4a"].usb

_orig_find_spec = importlib.util.find_spec
def _mock_find_spec(name, package=None):
    if name in["usbserial4a", "jnius", "usb4a", "usb4a.usb"]: return sys.modules[name].__spec__
    return _orig_find_spec(name, package)
importlib.util.find_spec = _mock_find_spec

# --- 2. IMPORT RNS ---
import RNS
try:
    import RNS.vendor.platformutils as pu
    pu.is_android = lambda: False
except: pass

from LXMF import LXMRouter
from RNS.Interfaces.Android.RNodeInterface import RNodeInterface
from RNS.Interfaces.Interface import Interface

signal.signal = lambda sig, handler: None

kotlin_cb = None
local_destination = None

# --- 3. SMART TIMESTAMP PARSER ---
def parse_timestamp(ts_raw):
    """Converts either Unix Int or YYYY-MM-DD string to Unix Long"""
    try:
        # Try if it's already a number (Unix Epoch)
        return int(float(ts_raw))
    except ValueError:
        try:
            # Try parsing the format seen in logs: 2026-03-19 16:17:58
            dt = datetime.strptime(ts_raw.strip(), "%Y-%m-%d %H:%M:%S")
            return int(dt.timestamp())
        except Exception as e:
            print(f"RNS-LOG: Failed to parse date {ts_raw}: {e}")
            return int(time.time())

# --- 4. DISCOVERY HANDLER ---
class DiscoveryHandler:
    def __init__(self):
        self.aspect_filter = None 

    def received_announce(self, destination_hash, announced_identity, app_data):
        try:
            h = RNS.hexrep(destination_hash, False)
            # Harvesters often send nickname in the app_data field of the announce
            n = app_data.decode("utf-8") if app_data else "Unknown Harvester"
            print(f"RNS-LOG: Discovered Node {h} ({n})")
            if kotlin_cb:
                kotlin_cb.onNodeDiscovered(h, n)
        except Exception as e:
            print(f"RNS-LOG: Discovery callback error: {e}")

def start_engine(service_obj, storage_path, radio_params_json=None):
    global kotlin_cb, local_destination
    kotlin_cb = service_obj
    
    try:
        rns_dir = os.path.join(storage_path, ".reticulum")
        lxmf_dir = os.path.join(storage_path, ".lxmf")
        
        if os.path.exists(rns_dir): shutil.rmtree(rns_dir)
        os.makedirs(rns_dir)
        if not os.path.exists(lxmf_dir): os.makedirs(lxmf_dir)
        
        with open(os.path.join(rns_dir, "config"), "w") as f:
            f.write("[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]\n")

        RNS.Reticulum(configdir=rns_dir, loglevel=RNS.LOG_DEBUG)
        
        id_path = os.path.join(storage_path, "storage_identity")
        local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
        if not os.path.exists(id_path): local_id.to_file(id_path)
        
        router = LXMRouter(identity=local_id, storagepath=lxmf_dir)
        local_destination = router.register_delivery_identity(local_id, display_name="PalmReceiver")
        router.register_delivery_callback(on_lxmf)
        
        # REGISTER DISCOVERY HANDLER
        RNS.Transport.register_announce_handler(DiscoveryHandler())
        
        addr = RNS.hexrep(local_id.hash, False)
        service_obj.onStatusUpdate(f"RNS Online: {addr}")
        service_obj.updateLocalAddress(addr)
    except Exception as e:
        print(f"RNS-CRITICAL STARTUP ERROR:\n{traceback.format_exc()}")
        service_obj.onStatusUpdate(f"Init Error: {str(e)}")

def inject_rnode(radio_params_json):
    try:
        params = json.loads(radio_params_json)
        ictx = {
            "name": "Android RNode Bridge",
            "type": "RNodeInterface",
            "interface_enabled": True,
            "outgoing": True,
            "tcp_host": "127.0.0.1",
            "tcp_port": 7633,
            "frequency": int(params.get("freq", 915000000)),
            "bandwidth": int(params.get("bw", 125000)),
            "txpower": int(params.get("tx", 20)),
            "spreadingfactor": int(params.get("sf", 7)),
            "codingrate": int(params.get("cr", 5)),
            "flow_control": False
        }
        ifac = RNodeInterface(RNS.Transport, ictx)
        ifac.mode = Interface.MODE_FULL
        ifac.IN = True
        ifac.OUT = True
        RNS.Transport.interfaces.append(ifac)
        
        time.sleep(1)
        if local_destination: local_destination.announce()
        return "RNode Active"
    except Exception as e:
        print(f"RNS-CRITICAL INJECT ERROR:\n{traceback.format_exc()}")
        return f"Link Failed: {str(e)}"

def on_lxmf(lxm):
    try:
        content = lxm.content.decode("utf-8")
        if "harvester_id" in content:
            f_io = io.StringIO(content)
            reader = csv.DictReader(f_io)
            for row in reader:
                # USE THE SMART PARSER FOR THE TIMESTAMP
                ts = parse_timestamp(row.get('timestamp', ''))
                
                if kotlin_cb:
                    kotlin_cb.onHarvestReceived(
                        row['id'], row['harvester_id'], row['block_id'],
                        int(row['ripe_bunches']), int(row['empty_bunches']),
                        float(row['latitude']), float(row['longitude']),
                        ts, row.get('photo_file', "")
                    )
    except Exception as e:
        print(f"RNS-CRITICAL LXMF ERROR:\n{traceback.format_exc()}")