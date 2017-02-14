function docker_machine_active() {
    local DM_ACTIVE
    DM_ACTIVE=`docker-machine active`
    if [ $? != 0 -o -z "$DM_ACTIVE" ]; then
        echo >&2 "No active Docker Machine"
        exit 1
    else
        echo $DM_ACTIVE
    fi
}

function docker_machine_ip() {
    local machine=$(docker_machine_active)
    local DM_IP
    DM_IP=`docker-machine ip ${machine}`
    if [ $? != 0 -o -z "${DM_IP}" ]; then
        echo >&2 "Error getting IP for active Docker Machine"
        exit 1
    else
        echo $DM_IP
    fi
}

function docker_host_ip() {
    if [ -z "${DOCKER_HOST}" ]; then
        echo >&2 "DOCKER_HOST is not set!"
        return 1
    fi

    
    if [[ "${DOCKER_HOST}" =~ tcp://(.*):.* ]]; then
        echo ${BASH_REMATCH[1]};
        return 0
    else
        echo >&2 "Invalid DOCKER_HOST value: ${DOCKER_HOST}"
        return 1
    fi
}

function container_running() {
    local containerId=$1

    local containerStatus
    containerStatus=$(docker inspect -f "{{.State.Running}}" $containerId 2> /dev/null)
    if [ $? != 0 -o "${containerStatus}" != "true" ]; then
        return 1
    else
        return 0
    fi    
}

function container_ip() {
    local containerId=$1

    local containerIp
    containerIp=$(docker inspect -f "{{.NetworkSettings.IPAddress}}" $containerId 2> /dev/null)
    if [ $? != 0 ]; then
        return 1
    else
        echo $containerIp
        return 0
    fi    
}

function container_exposed_port() {
    local containerId=$1
    local port=$2

    local exposedPort
    exposedPort=$(docker inspect -f "{{(index (index .NetworkSettings.Ports \"$port\") 0).HostPort}}" $containerId) 2> /dev/null
    if [ $? == 0 ]; then
        echo $exposedPort
        return 0
    else
        return 1
    fi
}

function waitFor() {
  local addr=$1
  local port=$2

  echo "Waiting for: $addr:$port"
  while ! nc -z -w 1 $addr $port 1>/dev/null 2>/dev/null; do
    echo -n "."
    sleep 1
  done
  echo ""
  echo "$addr:$port is ready!"
}

