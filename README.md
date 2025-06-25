# Image Processing Pipeline – CUDA-Ready Cluster

## Overview
This repository contains a minimal yet practical cluster that distributes image-processing workloads across multiple containers. A lightweight Java scheduler exposes a REST API for job management, while one or more Python workers (optionally GPU-accelerated via OpenCV CUDA) pull tasks, process images, and persist the results to a shared volume. Everything runs locally using Docker Compose and can be scaled horizontally in seconds.

## Core Components
| Component | Language | Responsibilities |
|-----------|----------|------------------|
| **Scheduler** (`scheduler/`) | Java 17, Spring Boot | - Accepts job submissions<br>- Queues jobs in memory<br>- Hands the next job to the first free worker<br>- Tracks and exposes job status |
| **Worker** (`worker/`) | Python 3.10, OpenCV | - Polls the scheduler for new jobs<br>- Runs a denoise + Canny edge-detection pipeline (uses GPU when available)<br>- Stores the result under `/data/output` and notifies the scheduler |
| **Shared volume** (`shared_data/`) | – | Acts as a simple network file system between containers: <br>`shared_data/input` – raw images <br>`shared_data/output` – processed results |

## Folder Layout
```
.
├── docker-compose.yml         # One-command local cluster
├── scheduler/                 # Spring Boot microservice
│   └── ...
├── worker/                    # Python worker node
│   └── ...
└── shared_data/
    ├── input/                 # Place images to be processed here
    └── output/                # Results appear here automatically
```

## Prerequisites
1. **Docker 20.10+** (or Podman compatible with Compose).
2. **Optional – GPU acceleration**
   - NVIDIA GPU & drivers
   - [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html)
   - Adjust `worker/Dockerfile` base image to a CUDA variant (e.g. `nvidia/cuda:12.4.1-runtime-ubuntu22.04`).

## Getting Started
```bash
# 1. Clone the repository
git clone https://github.com/wangqin0/image-processing-pipeline-CUDA-cluster.git
cd image-processing-pipeline-CUDA-cluster

# 2. Add one or more input images
cp /path/to/local/photo.jpg shared_data/input/

# 3. Build and start everything
docker compose up --build
# The scheduler is now available on http://localhost:8080

# 4. Submit a job (from another terminal)
ID=$(curl -s -X POST "http://localhost:8080/jobs" \
          -d imagePath=/data/input/photo.jpg)
echo "Job ID: $ID"

# 5. Follow job status
watch -n1 curl -s http://localhost:8080/jobs/${ID}/status

# 6. Inspect the results once status==COMPLETED
ls shared_data/output
```

### Scaling Out
Spin up additional workers on the fly for addtional compute capacity:
```bash
docker compose up --scale worker=4 --build
```
The scheduler is stateless and requires no changes.

## API Reference
| Method | Endpoint | Payload / Params | Description |
|--------|----------|------------------|-------------|
| `POST` | `/jobs` | `imagePath=<absolute path inside /data>` | Enqueue a new processing task. Returns the `jobId`. |
| `GET` | `/jobs/next` | – | (Called by workers) Retrieve the next queued job or `204 No Content` if none available. |
| `POST` | `/jobs/{id}/complete` | – | (Called by workers) Mark a job as finished. |
| `GET` | `/jobs/{id}/status` | – | Poll the current status (`QUEUED`, `IN_PROGRESS`, `COMPLETED`, `UNKNOWN`). |

## Customising the Pipeline
* **Change the algorithm** – Tweak `worker/worker.py` to swap in another OpenCV call or an entirely different library.
* **Persist data elsewhere** – Mount a cloud bucket or network share at `/data`.
* **Add authentication** – Wrap the scheduler endpoints with Spring Security.

## Troubleshooting
- _Scheduler not reachable_ – ensure port `8080` isn't already in use and that containers are healthy (`docker compose ps`).
- _CUDA unavailable inside worker_ – confirm `nvidia-smi` works in a regular container; if not install the container toolkit.

## License
This project is released under the MIT License – see `LICENSE` for details. 