import sys, os, csv, io, json, signal, time
from types import ModuleType
import importlib.util, importlib.machinery

# --- THE DEEP ANDROID MOCK FIX ---
def mock_module(name):
    mock = ModuleType(name)
    mock.__spec__ = importlib.machinery.ModuleSpec(name, None)
    sys.modules[name] = mock
    return mock

# 1. Mock usbserial4a and its sub-attr
usbserial_mock = mock_module("usbserial4a")
usbserial_mock.serial4a = ModuleType("serial4a")
usbserial_mock.get_ports_list = lambda: []

# 2. Mock usb4a and the specific 'usb' name Reticulum wants to import
usb4a_mock = mock_module("usb4a")
usb4a_mock.usb = ModuleType("usb") # This fixes the "cannot import name 'usb'" error
sys.modules["usb4a.usb"] = usb4a_mock.usb 

# 3. Mock jnius
jnius_mock = mock_module("jnius")
jnius_mock.autoclass = lambda x: ModuleType("DummyClass")
jnius_mock.cast = lambda x, y: x

# 4. Overload find_spec
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

def start_engine(service_obj, storage_path, radio_params_json):
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
        # Now that mocks are perfect, Reticulum will initialize the interface
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