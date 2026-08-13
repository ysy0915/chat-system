#!/bin/bash
# Milvus 启动脚本 (v2.3.4 + etcd + minio)
echo "[milvus] 检查已有容器..."
docker ps --format '{{.Names}}' | grep -q milvus-standalone && echo "[milvus] 已在运行" && exit 0

docker network create milvus-net 2>/dev/null || true
mkdir -p /opt/app/milvus/etcd /opt/app/milvus/minio /opt/app/milvus/data

docker run -d --name milvus-etcd --network milvus-net \
  -e ETCD_AUTO_COMPACTION_MODE=revision \
  -e ETCD_AUTO_COMPACTION_RETENTION=1000 \
  -e ETCD_QUOTA_BACKEND_BYTES=4294967296 \
  -v /opt/app/milvus/etcd:/etcd \
  --restart unless-stopped \
  quay.io/coreos/etcd:v3.5.5 \
  etcd -advertise-client-urls=http://milvus-etcd:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd

docker run -d --name milvus-minio --network milvus-net \
  -e MINIO_ROOT_USER=${MINIO_ROOT_USER:-minioadmin} -e MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD:-minioadmin} \
  -v /opt/app/milvus/minio:/data --restart unless-stopped \
  minio/minio:RELEASE.2023-03-20T20-16-18Z server /data --console-address ':9001'

sleep 3

docker run -d --name milvus-standalone --network milvus-net \
  -p 19530:19530 -p 9091:9091 \
  -e ETCD_ENDPOINTS=milvus-etcd:2379 \
  -e MINIO_ADDRESS=milvus-minio:9000 \
  -e MINIO_ACCESS_KEY_ID=${MINIO_ROOT_USER:-minioadmin} \
  -e MINIO_SECRET_ACCESS_KEY=${MINIO_ROOT_PASSWORD:-minioadmin} \
  -v /opt/app/milvus/data:/var/lib/milvus \
  --restart unless-stopped \
  milvusdb/milvus:v2.3.4 /milvus/bin/milvus run standalone

echo "[milvus] 启动完成"
