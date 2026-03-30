import os, sys, platform, time, csv, io, json, signal
from types import ModuleType
import importlib.util, importlib.machinery

# --- 1. THE GLOBAL HIJACK ---
# Kill the Android flag before Reticulum even wakes up
platform.system = lambda: "Linux"

# --- 2. DEEP MOCKS ---
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

# --- 3. IMPORT RNS & DISABLE ANDROID VALIDATION ---
import RNS
# Forcefully tell Reticulum it is NOT on Android
try:
    import RNS.vendor.platformutils as pu
    pu.is_android = lambda: False
except:
    pass

import LXMF
from LXMF import LXMRouter
# IMPORTANT: Explicitly import the Universal/Linux RNodeInterface
from RNS.Interfaces.RNodeInterface import RNodeInterface
from RNS.Interfaces.Interface import Interface

signal.signal = lambda sig, handler: None

# --- 4. ENGINE LOGIC ---
kotlin_cb = None
router = None

def start_engine(service_obj, storage_path, radio_params_json):
    global kotlin_cb, router
    kotlin_cb = service_obj
    
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # Empty config - we inject everything manually
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
        service_obj.onStatusUpdate(f"RNS Init Error: {str(e)}")

def inject_rnode(radio_params_json):
    try:
        params = json.loads(radio_params_json)
        
        # We define a "Ghost" config. 
        # By not calling Transport.synthesize_interface, we skip the Android validator.
        ictx = {
            "name": "RNode-Mesh",
            "type": "RNodeInterface", # We keep this for internal RNS logic
            "enabled": True,
            "port": "tcp://127.0.0.1:8001",
            "frequency": params.get("freq") or 915000000,
            "bandwidth": params.get("bw") or 125000,
            "txpower": params.get("tx") or 20,
            "spreadingfactor": params.get("sf") or 7,
            "codingrate": params.get("cr") or 5,
            "flow_control": False
        }
        
        # Manual Instantiation of the Universal Driver
        # This bypasses the synthesized check that throws the "invalid interface" error
        ifac = RNodeInterface(RNS.Transport, ictx)
        ifac.mode = Interface.MODE_FULL
        
        # Add directly to the transport stack
        RNS.Transport.interfaces.append(ifac)
        
        return f"RNode Active: {params.get('freq')/1000000} MHz"
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