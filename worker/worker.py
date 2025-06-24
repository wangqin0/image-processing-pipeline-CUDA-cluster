import time, requests, cv2

SCHEDULER_URL = "http://scheduler:8080"
while True:
    resp = requests.get(f"{SCHEDULER_URL}/jobs/next")
    if resp.status_code != 200:
        time.sleep(0.5)
        continue
    job = resp.json()
    img = cv2.imread(job["imagePath"], cv2.IMREAD_GRAYSCALE)
    denoised = cv2.GaussianBlur(img, (5,5), 0)
    if cv2.cuda.getCudaEnabledDeviceCount() > 0:
        gpu_mat = cv2.cuda_GpuMat()
        gpu_mat.upload(denoised)
        detector = cv2.cuda.createCannyEdgeDetector(50,150)
        edges = detector.detect(gpu_mat).download()
    else:
        edges = cv2.Canny(denoised, 50, 150)
    cv2.imwrite(f"/data/output/{job['id']}_edges.png", edges)
    requests.post(f"{SCHEDULER_URL}/jobs/{job['id']}/complete")
