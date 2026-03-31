import os, sys, time, platform, json, csv, io, signal, warnings, shutil, traceback
from types import ModuleType
import importlib.util, importlib.machinery

# --- 1. SILENCE WARNINGS ---
warnings.filterwarnings("ignore", category=DeprecationWarning)
warnings.filterwarnings("ignore", category=ResourceWarning) # Stops log spam for unclosed files

# --- 2. THE HIJACKS & MOCKS ---
platform.system = lambda: "Linux"
def mock_module(name):
    if name not in sys.modules:
        mock = ModuleType(name)
        mock.__spec__ = importlib.machinery.ModuleSpec(name, None)
        if name == "usbserial4a": mock.get_ports_list = lambda:[]
        if name == "jnius":
            class Dummy:
                def autoclass(self, n): return self
                def cast(self, x, y): return x
            mock.autoclass = lambda x: Dummy()
        if name == "usb4a":
            mock.usb = ModuleType("usb"); mock.usb.get_usb_device_list = lambda:[]
        sys.modules[name] = mock
mock_module("usbserial4a"); mock_module("usb4a"); mock_module("jnius")
sys.modules["usb4a.usb"] = sys.modules["usb4a"].usb

import RNS
try:
    import RNS.vendor.platformutils as pu
    pu.is_android = lambda: False
except: pass

from LXMF import LXMRouter
from RNS.Interfaces.RNodeInterface import RNodeInterface
from RNS.Interfaces.Interface import Interface

signal.signal = lambda sig, handler: None

kotlin_cb = None
local_destination = None

class DiscoveryHandler:
    def __init__(self):
        self.aspect_filter = None 

    def received_announce(self, destination_hash, announced_identity, app_data):
        try:
            h = RNS.hexrep(destination_hash, False)
            n = app_data.decode("utf-8") if app_data else "Unknown Harvester"
            if kotlin_cb: kotlin_cb.onNodeDiscovered(h, n)
        except Exception as e:
            print(f"RNS-LOG: Discovery error: {e}")

def start_engine(service_obj, storage_path, radio_params_json=None):
    global kotlin_cb, local_destination
    kotlin_cb = service_obj
    
    try:
        rns_dir = os.path.join(storage_path, ".reticulum")
        lxmf_dir = os.path.join(storage_path, ".lxmf")
        
        # WIPE CORRUPTED CACHES: Prevents File Descriptor exhaustion and Ratchet crashes
        if os.path.exists(rns_dir): shutil.rmtree(rns_dir)
        if os.path.exists(lxmf_dir): shutil.rmtree(lxmf_dir)
        
        os.makedirs(rns_dir)
        os.makedirs(lxmf_dir)
        
        with open(os.path.join(rns_dir, "config"), "w") as f:
            f.write("[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]\n")

        RNS.Reticulum(configdir=rns_dir)
        
        id_path = os.path.join(storage_path, "storage_identity")
        local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
        if not os.path.exists(id_path): local_id.to_file(id_path)
        
        router = LXMRouter(identity=local_id, storagepath=lxmf_dir)
        local_destination = router.register_delivery_identity(local_id, display_name="PalmReceiver")
        router.register_delivery_callback(lambda lxm: on_lxmf(lxm, service_obj))
        
        RNS.Transport.register_announce_handler(DiscoveryHandler())
        
        addr = RNS.hexrep(local_destination.hash, False)
        service_obj.onStatusUpdate(f"RNS Online: {addr}")
        service_obj.updateLocalAddress(addr)
        
    except Exception as e:
        # BROAD EXCEPTION CATCH: Print the exact trace to logcat instead of silent death
        err_trace = traceback.format_exc()
        print(f"RNS-CRITICAL STARTUP ERROR:\n{err_trace}")
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
            "frequency": int(params.get("freq")),
            "bandwidth": int(params.get("bw")),
            "txpower": int(params.get("tx")),
            "spreadingfactor": int(params.get("sf")),
            "codingrate": int(params.get("cr")),
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
        err_trace = traceback.format_exc()
        print(f"RNS-CRITICAL INJECT ERROR:\n{err_trace}")
        return f"Link Failed: {str(e)}"

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
        err_trace = traceback.format_exc()
        print(f"RNS-CRITICAL LXMF ERROR:\n{err_trace}")
        if service_obj: service_obj.onStatusUpdate(f"Data Error: {str(e)}")