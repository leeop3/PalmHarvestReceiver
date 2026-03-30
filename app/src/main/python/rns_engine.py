import os, sys, time, platform, json, csv, io, signal, warnings
from types import ModuleType
import importlib.util, importlib.machinery

warnings.filterwarnings("ignore", category=DeprecationWarning)

# --- HIJACKS ---
platform.system = lambda: "Linux"
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
            mock.usb = ModuleType("usb"); mock.usb.get_usb_device_list = lambda: []
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

def start_engine(service_obj, storage_path, radio_params_json=None):
    global kotlin_cb, local_destination
    kotlin_cb = service_obj
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # CRITICAL: WIPE old config to stop the "[Errno 2] TCP" error loop
    with open(os.path.join(rns_dir, "config"), "w") as f:
        f.write("[reticulum]\nenable_transport = True\n\n[interfaces]\n")

    try:
        # Start RNS with Debug Logging for ADB
        RNS.Reticulum(configdir=rns_dir, loglevel=RNS.LOG_DEBUG)
        
        id_path = os.path.join(storage_path, "storage_identity")
        local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
        if not os.path.exists(id_path): local_id.to_file(id_path)
        
        router = LXMRouter(identity=local_id, storagepath=os.path.join(storage_path, ".lxmf"))
        local_destination = router.register_delivery_identity(local_id, display_name="PalmReceiver")
        router.register_delivery_callback(on_lxmf)
        
        service_obj.onStatusUpdate(f"RNS Online: {RNS.hexrep(local_destination.hash, False)}")
    except Exception as e:
        service_obj.onStatusUpdate(f"Init Error: {str(e)}")

def inject_rnode(radio_params_json):
    try:
        params = json.loads(radio_params_json)
        print("RNS-LOG: Attempting to inject RNode via TCP 127.0.0.1:8001")
        
        ictx = {
            "name": "RNode-Bridge",
            "type": "RNodeInterface",
            "enabled": True,
            "port": None,            # Trigger TCP mode in driver
            "tcp_host": "127.0.0.1",
            "tcp_port": 8001,
            "frequency": int(params.get("freq")),
            "bandwidth": int(params.get("bw")),
            "txpower": int(params.get("tx")),
            "spreadingfactor": int(params.get("sf")),
            "codingrate": int(params.get("cr")),
            "flow_control": False
        }
        
        # Instantiate and add to transport
        ifac = RNodeInterface(RNS.Transport, ictx)
        ifac.mode = Interface.MODE_FULL
        RNS.Transport.interfaces.append(ifac)
        
        if local_destination: local_destination.announce()
        return "Hardware Handshake Sent"
    except Exception as e:
        return f"Injection Error: {str(e)}"

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
        if kotlin_cb: kotlin_cb.onStatusUpdate(f"Data Error: {e}")