#!/bin/bash
set -x

spool_dir="/var/spool/wavefront-proxy"
mkdir -p $spool_dir

WAVEFRONT_HOSTNAME=${WAVEFRONT_HOSTNAME:-$(hostname)}
export WAVEFRONT_HOSTNAME

# HACK, we can remove this sed once we release 3.25, which honors DO_SERVICE_RESTART.
autoconf=/opt/wavefront/wavefront-proxy/bin/autoconf-wavefront-proxy.sh
sed -i"" "/init\.d/d" $autoconf
/bin/bash -x $autoconf

#logfile=/var/log/wavefront.log
java_heap_usage=${JAVA_HEAP_USAGE:-4G}
java \
	-Xmx$java_heap_usage -Xms$java_heap_usage \
	-jar /opt/wavefront/wavefront-proxy/bin/wavefront-push-agent.jar \
	-f /opt/wavefront/wavefront-proxy/conf/wavefront.conf

#tail -f $logfile
