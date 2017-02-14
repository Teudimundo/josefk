#!/bin/bash


TOPICS=(
    josefk-testing1:2:1
    josefk-testing2:2:1
)


SCRIPT_PATH=`dirname "$0"`
SCRIPT_PATH=`( cd "$SCRIPT_PATH" && pwd )`
source ${SCRIPT_PATH}/common.sh

echo "Removing old containers"
docker rm -fv josefk-zookeeper-dev josefk-kafka-dev

DM_IP=$(docker_machine_ip)

set -e
echo "Spawning new Zookeeper container: ${DM_IP}:2181"
docker run -d --name josefk-zookeeper-dev \
       -p 2181:2181 \
       -h zookeeper \
       wurstmeister/zookeeper:3.4.6


echo "Spawning new Kafka container: ${DM_IP}:9092"

function mkTopicsStr() {
    local IFS=","
    echo "$*"
}

_TOPICS=$(mkTopicsStr "${TOPICS[@]}")
docker run -d --name josefk-kafka-dev \
       -p 0.0.0.0:9092:9092 \
       -h kafka \
       -e KAFKA_BROKER_ID=1 \
       -e KAFKA_ZOOKEEPER_CONNECT=${DM_IP}:2181 \
       -e KAFKA_ADVERTISED_HOST_NAME=${DM_IP} \
       -e KAFKA_ADVERTISED_PORT=9092 \
       -e KAFKA_CREATE_TOPICS=${_TOPICS} \
       wurstmeister/kafka:0.10.0.0

waitFor ${DM_IP} 9092
echo "Kafka started"
