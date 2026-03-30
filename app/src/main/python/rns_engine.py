import os
import sys
import platform
import time
import csv
import io
import json
import signal
from types import ModuleType
import importlib.util
import importlib.machinery

# --- 1. THE PLATFORM HIJACK ---
# Force Reticulum to use the standard Linux/TCP driver instead of the Android hardware driver
platform.system = lambda: "Linux"

# --- 2. THE MODULE MOCKS ---
# Prevent ImportErrors for Kivy/Android-only libraries that RNS looks for
def mock_kivy_libs():
    for lib_name in ["usbserial4a", "usb4a", "jnius"]:
        if lib_name not in sys.modules:
            mock = ModuleType(lib_name)
            mock.__spec__ = importlib.machinery.ModuleSpec(lib_name, None)
            
            if lib_name == "usbserial4a":
                mock.get_ports_list = lambda: []
                mock.serial4a = ModuleType("serial4a")
            if lib_name == "usb4a":
                mock.usb = ModuleType("usb")
                mock.usb.get_usb_device_list = lambda: []
            if lib_name == "jnius":
                class Dummy:
                    def autoclass(self, name): return self
                    def cast(self, x, y): return x
                mock.autoclass = lambda x: Dummy()
                mock.cast = lambda x, y: x
                
            sys.modules[lib_name] = mock

mock_kivy_libs()

# Override find_spec to satisfy internal RNS importlib calls
_orig_find_spec = importlib.util.find_spec
def _mock_find_spec(name, package=None):
    if name in ["usbserial4a", "jnius", "usb4a"]:
        return sys.modules[name].__spec__
    return _orig_find_spec(name, package)
importlib.util.find_spec = _mock_find_spec

# --- 3. IMPORT RNS & HIJACK INTERNAL UTILS ---
import RNS
import LXMF
from LXMF import LXMRouter
from RNS.Interfaces.Interface import Interface

# Force internal Reticulum check to return False for Android
try:
    import RNS.vendor.platformutils as pu
    pu.is_android = lambda: False
except:
    pass

# Disable signals for Android compatibility
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
    
    # Empty config to prevent boot-time hardware checks
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
        service_obj.onStatusUpdate(f"RNS Init Error: {str(e)}")

def inject_rnode(radio_params_json):
    # Called by Kotlin once the Bluetooth bridge (ServerSocket) is ready
    params = json.loads(radio_params_json)
    try:
        # We use the standard RNodeInterface which now handles TCP sockets natively 
        # because we hijacked the platform as "Linux"
        from RNS.Interfaces.RNodeInterface import RNodeInterface
        
        ictx = {
            "name": "RNode-Bridge",
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
        
        ifac = RNodeInterface(RNS.Transport, ictx)
        ifac.mode = Interface.MODE_FULL
        RNS.Transport.interfaces.append(ifac)
        return f"Interface Injected: {params.get('freq')/1000000} MHz"
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