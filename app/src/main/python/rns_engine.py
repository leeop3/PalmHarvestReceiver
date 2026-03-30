import signal
signal.signal = lambda sig, handler: None
import RNS, LXMF, os, csv, io
from LXMF import LXMRouter

def start_engine(service_obj, storage_path, radio_params):
    rns_dir = os.path.join(storage_path, ".reticulum")
    if not os.path.exists(rns_dir): os.makedirs(rns_dir)
    
    # Get parameters safely from the Python dictionary
    freq = radio_params.get("freq", 915000000)
    bw = radio_params.get("bw", 125000)
    tx = radio_params.get("tx", 20)
    sf = radio_params.get("sf", 7)
    cr = radio_params.get("cr", 5)

    config = f"""
[reticulum]
enable_transport = True
share_instance = Yes

[interfaces]
  [[TCP Bridge]]
    type = TCPClientInterface
    enabled = True
    target_host = 127.0.0.1
    target_port = 8001

  [[RNode Interface]]
    type = RNodeInterface
    enabled = True
    port = /dev/ttyTCPBridge
    frequency = {freq}
    bandwidth = {bw}
    txpower = {tx}
    spreadingfactor = {sf}
    codingrate = {cr}
"""
    with open(os.path.join(rns_dir, "config"), "w") as f:
        f.write(config)

    RNS.Reticulum(configdir=rns_dir)
    # LXMF Logic...
    service_obj.onStatusUpdate(f"RNode Configured: {freq/1000000} MHz")