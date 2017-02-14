#!/bin/sh

SCRIPT_PATH=`dirname "$0"`
SCRIPT_PATH=`( cd "$SCRIPT_PATH" && pwd )`
source ${SCRIPT_PATH}/common.sh

DM_IP=$(docker_machine_ip)

TOPIC=$1
if [ -z "$TOPIC" ]; then
    echo >&2 "No topic specified!"
    exit 2
fi
shift

if [ -n "$KAFKA_CONSUMER_GROUP" ]; then

docker exec -i josefk-kafka-dev sh -c "cat > /tmp/consumer.config.$$.txt" <<EOF
group.id=$KAFKA_CONSUMER_GROUP
EOF
CONSUMER_CONFIG_OPT="--consumer.config /tmp/consumer.config.$$.txt"

fi

docker exec -ti josefk-kafka-dev /opt/kafka_2.11-0.10.0.0/bin/kafka-console-consumer.sh $CONSUMER_CONFIG_OPT --new-consumer --bootstrap-server localhost:9092 --topic ${TOPIC} $*
