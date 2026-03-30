import signal
signal.signal = lambda sig, handler: None
import RNS, LXMF, os, csv, io
from LXMF import LXMRouter

def start_engine(service_obj, storage_path, radio_params):
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # Extract radio params from the dictionary passed by Kotlin
    f = radio_params.get("freq", 915000000)
    bw = radio_params.get("bw", 125000)
    tx = radio_params.get("tx", 20)
    sf = radio_params.get("sf", 7)
    cr = radio_params.get("cr", 5)

    config = f"""
[reticulum]
enable_transport = True
share_instance = Yes

[interfaces]
  [[RNode Interface]]
    type = RNodeInterface
    enabled = True
    port = /dev/ttyTCPBridge  # Placeholder for the TCP Bridge
    frequency = {f}
    bandwidth = {bw}
    txpower = {tx}
    spreadingfactor = {sf}
    codingrate = {cr}
    flow_control = False
    
  [[TCP Bridge]]
    type = TCPClientInterface
    enabled = True
    target_host = 127.0.0.1
    target_port = 8001
"""
    with open(os.path.join(rns_dir, "config"), "w") as f:
        f.write(config)

    RNS.Reticulum(configdir=rns_dir)
    # ... rest of LXMF init ...
    service_obj.onStatusUpdate("RNS Engine Reconfigured")