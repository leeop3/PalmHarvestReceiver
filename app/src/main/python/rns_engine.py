import RNS, LXMF, csv, io, time
from LXMF import LXMRouter

kotlin_callback = None
router = None

def start_engine(callback_obj):
    global kotlin_callback, router
    kotlin_callback = callback_obj
    # RNS Initialization logic here...
    pass

def on_lxmf(lxm):
    try:
        content = lxm.content.decode("utf-8")
        if content.startswith("id,"):
            f = io.StringIO(content)
            reader = csv.DictReader(f)
            for row in reader:
                kotlin_callback.onHarvestReceived(
                    row['id'], row['harvester_id'], row['block_id'],
                    int(row['ripe_bunches']), int(row['empty_bunches']),
                    float(row['latitude']), float(row['longitude']),
                    int(row['timestamp']), row.get('photo_file', "")
                )
    except Exception as e:
        print(f"Error: {e}")
