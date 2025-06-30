import time, requests, cv2
import os

SCHEDULER_URL = os.getenv("SCHEDULER_URL", "http://scheduler:8080")
OUTPUT_DIR = os.getenv("OUTPUT_DIR", "/data/output")

# Polling interval in milliseconds (shared with scheduler). Must be plain integer.
_interval_raw = os.getenv("WORKER_PULL_INTERVAL", "1000").strip()
if not _interval_raw.isdigit() or int(_interval_raw) <= 0:
    raise ValueError(
        f"Invalid WORKER_PULL_INTERVAL: '{_interval_raw}'. Expected positive integer milliseconds.")

WORKER_PULL_INTERVAL_MS = int(_interval_raw)
WORKER_PULL_INTERVAL_SEC = WORKER_PULL_INTERVAL_MS / 1000.0

def wait_for_scheduler():
    print("Waiting for scheduler to be ready...")
    while True:
        try:
            resp = requests.get(f"{SCHEDULER_URL}/jobs/next", timeout=5)
            print("Scheduler is ready!")
            return
        except requests.exceptions.RequestException as e:
            print(f"Scheduler not ready yet, retrying in {WORKER_PULL_INTERVAL_SEC} seconds... ({e})")
            time.sleep(WORKER_PULL_INTERVAL_SEC)

wait_for_scheduler()

while True:
    try:
        resp = requests.get(f"{SCHEDULER_URL}/jobs/next")
        if resp.status_code != 200:
            time.sleep(WORKER_PULL_INTERVAL_SEC)
            continue
        job = resp.json()
        img = cv2.imread(job["imagePath"], cv2.IMREAD_GRAYSCALE)
        denoised = cv2.GaussianBlur(img, (5,5), 0)
        if cv2.cuda.getCudaEnabledDeviceCount() > 0:
            print("Using CUDA for job", job["id"])
            gpu_mat = cv2.cuda_GpuMat()
            gpu_mat.upload(denoised)
            detector = cv2.cuda.createCannyEdgeDetector(50,150)
            edges = detector.detect(gpu_mat).download()
        else:
            print("Using CPU for job", job["id"])
            edges = cv2.Canny(denoised, 50, 150)
        cv2.imwrite(f"/data/output/{job['id']}_edges.png", edges)
        requests.post(f"{SCHEDULER_URL}/jobs/{job['id']}/complete")
    except requests.exceptions.RequestException as e:
        print(f"Error communicating with scheduler: {e}, retrying in {WORKER_PULL_INTERVAL_SEC} seconds...")
        time.sleep(WORKER_PULL_INTERVAL_SEC)
    except Exception as e:
        print(f"Error processing job: {e}, retrying in {WORKER_PULL_INTERVAL_SEC} seconds...")
        time.sleep(WORKER_PULL_INTERVAL_SEC)
